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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
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
     * S4502: CSRF is intentionally disabled.
     * This is a stateless REST/FHIR API that uses Bearer (JWT) tokens, not cookies.
     * CSRF attacks require a browser session with cookies; they cannot exploit Bearer-token auth.
     * Disabling CSRF is correct and required here — leaving it enabled would cause 403 on
     * every POST/PUT/DELETE from FHIR clients that do not send a CSRF token.
     *
     * <p>JWT validation: tokens must be issued by the configured Keycloak realm
     * (spring.security.oauth2.resourceserver.jwt.issuer-uri) and carry the
     * "record-locator" scope. Spring validates the issuer and expiry automatically;
     * the scope check is enforced via hasAuthority("SCOPE_record-locator").
     *
     * <p>Keycloak puts scopes in the "scope" claim as a space-separated string.
     * JwtGrantedAuthoritiesConverter reads that claim and prefixes each scope with
     * "SCOPE_", so "record-locator" in the token becomes SCOPE_record-locator here.
     *
     * <p>Node-to-node endpoints (/consent-events, /pointer-events) are also protected
     * by JWT. mTLS adds a second layer once the reverse proxy is deployed.
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
                        // All other endpoints require a valid JWT with the record-locator scope.
                        // Keycloak encodes scopes in the "scope" claim; Spring prefixes them
                        // with SCOPE_ so "record-locator" → SCOPE_record-locator.
                        .anyRequest().hasAuthority("SCOPE_record-locator"))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtConverter())))
                .exceptionHandling(ex -> ex
                        // Return 401 with WWW-Authenticate: Bearer when no/invalid token
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        // Return 403 when authenticated but lacking the record-locator scope
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler()))
                .addFilterBefore(mtlsHeaderFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Keycloak puts granted scopes in the JWT "scope" claim as a space-separated
     * string (e.g. "openid profile record-locator"). The default Spring converter
     * reads "scope" and prefixes each value with "SCOPE_", producing the authority
     * SCOPE_record-locator that the filter chain checks above.
     *
     * No custom roles mapping needed: scope-based access is sufficient here.
     * If role-based access is needed later, add a second converter that reads
     * realm_access.roles or resource_access.<client>.roles from the Keycloak token.
     */
    @Bean
    public JwtAuthenticationConverter keycloakJwtConverter() {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        // Keycloak uses "scope" (the default), no override needed.
        // Prefix stays "SCOPE_" (the default).
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(scopeConverter);
        return converter;
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
