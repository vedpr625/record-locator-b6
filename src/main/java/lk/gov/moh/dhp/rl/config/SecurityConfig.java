package lk.gov.moh.dhp.rl.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final Environment environment;

    @Value("${rl.security.mtls-header-enabled:false}")
    private boolean mtlsEnabled;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * S-1: Warn loudly at startup if the "dev" Spring profile is active.
     * The dev filter chain permits all requests without authentication, so
     * accidentally deploying with "dev" active exposes every endpoint.
     *
     * S-2: Warn at startup when mTLS header enforcement is disabled.
     * The node-to-node endpoints (/consent-events, /pointer-events) rely on
     * the reverse proxy setting a client-cert verification header; without
     * mTLS, any caller on the network can POST to these endpoints.
     */
    @PostConstruct
    void warnInsecureConfig() {
        // S-1: dev profile guard
        if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
            log.warn("*** SECURITY WARNING: Spring profile 'dev' is active — " +
                     "all endpoints are unauthenticated. DO NOT run this profile in production. ***");
        }
        // S-2: mTLS disabled warning
        if (!mtlsEnabled) {
            log.warn("rl.security.mtls-header-enabled=false — mTLS header enforcement is OFF. " +
                     "Node-to-node endpoints (/consent-events, /pointer-events) are not protected " +
                     "by client-certificate verification. Enable once your reverse proxy is deployed.");
        }
    }

    /**
     * /consent-events and /pointer-events are node-to-node (Bundle 2a
     * pushing consent changes, a child node pushing pointers), not
     * ordinary end-user traffic. The real design is mTLS enforced at
     * a reverse proxy (nginx/Envoy) in front of this app, with the
     * proxy forwarding a header ONLY on successful client-certificate
     * verification - this app never touches certificates itself.
     *
     * Deliberately NOT enabled yet - waiting on real certificates and
     * the proxy actually being deployed. rl.security.mtls-header-enabled
     * defaults to false, so today both endpoints still only sit
     * behind the ordinary bearer-token rule below - the SAME
     * documented gap as before, now with the actual mechanism built
     * and ready rather than left to write later. Flip the property to
     * true once nginx is genuinely in front of this app and verified
     * to be stripping/overwriting any client-supplied value of the
     * header name below (a caller must never be able to set this
     * header themselves and have it trusted).
     */
    /**
     * S4502: CSRF is intentionally disabled.
     * This is a stateless REST/FHIR API that uses Bearer (JWT) tokens, not cookies.
     * CSRF attacks require a browser session with cookies; they cannot exploit Bearer-token auth.
     * Disabling CSRF is correct and required here — leaving it enabled would cause 403 on
     * every POST/PUT/DELETE from FHIR clients that do not send a CSRF token.
     */
    @Bean
    @Profile("!dev")
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain productionFilterChain(HttpSecurity http, MtlsHeaderFilter mtlsHeaderFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .exceptionHandling(ex -> ex
                        // Return 401 with WWW-Authenticate: Bearer when no/invalid token
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        // Return 403 when authenticated but lacking required scope/role
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler()))
                .addFilterBefore(mtlsHeaderFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * S4502: CSRF is intentionally disabled for the dev profile.
     * This is a stateless REST/FHIR API with Bearer tokens, not browser cookies.
     * In dev mode, CSRF is disabled only for the relevant paths (H2 console, FHIR endpoints)
     * rather than globally, so this chain cannot be accidentally promoted to production.
     */
    @Bean
    @Profile("dev")
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/h2-console/**", "/fhir/**",
                        "/pointer-events/**", "/consent-events/**"))
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    public MtlsHeaderFilter mtlsHeaderFilter(
            @Value("${rl.security.mtls-header-enabled:false}") boolean enabled,
            @Value("${rl.security.mtls-header-name:X-Client-Cert-Verified}") String headerName,
            @Value("${rl.security.mtls-header-success-value:SUCCESS}") String successValue) {
        return new MtlsHeaderFilter(enabled, headerName, successValue);
    }

    /**
     * Rejects /consent-events and /pointer-events unless the
     * configured header is present - only meaningful once enabled
     * AND a reverse proxy is genuinely in front of this app setting
     * that header itself after real client-certificate verification.
     * With enabled=false (the current default) this filter does
     * nothing at all, on any endpoint.
     */
    static class MtlsHeaderFilter extends OncePerRequestFilter {

        private final boolean enabled;
        private final String headerName;
        /**
         * The exact value the TLS-terminating reverse proxy sets on successful
         * client-certificate verification (e.g. "SUCCESS").  Any other value —
         * including a forged non-null header from a caller — is rejected.
         * Configurable via {@code rl.security.mtls-header-success-value}.
         */
        private final String successValue;

        MtlsHeaderFilter(boolean enabled, String headerName, String successValue) {
            this.enabled = enabled;
            this.headerName = headerName;
            this.successValue = successValue;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                         FilterChain chain) throws ServletException, IOException {
            String path = request.getRequestURI();
            boolean isNodeToNodeEndpoint = path.equals("/consent-events") || path.equals("/pointer-events");

            if (enabled && isNodeToNodeEndpoint
                    && !successValue.equals(request.getHeader(headerName))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "client certificate verification required");
                return;
            }

            chain.doFilter(request, response);
        }
    }
}
