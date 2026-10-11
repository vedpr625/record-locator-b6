package lk.gov.moh.dhp.rl.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
public class SecurityConfig {

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
    @Bean
    @Profile("!dev")
    public SecurityFilterChain productionFilterChain(HttpSecurity http, MtlsHeaderFilter mtlsHeaderFilter)
            throws Exception {
        http
                // Stateless JWT API: no session, no CSRF tokens needed.
                // CSRF protection is meaningful only for browser sessions with cookies;
                // this API uses Bearer tokens, so CSRF is both unnecessary and incorrect
                // (it would cause 403 on all POST/PUT/DELETE without CSRF headers).
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

    @Bean
    @Profile("dev")
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // CSRF is not meaningful for this stateless FHIR API (no cookies,
                // no browser sessions). Disable only for the H2 console and FHIR/
                // ingest paths rather than globally, so the dev chain cannot be
                // accidentally promoted into an environment where CSRF matters.
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
