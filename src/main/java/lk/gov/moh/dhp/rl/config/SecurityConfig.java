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
     * <p>Two scopes control access (both issued by the Keycloak bundle-5 realm):
     * <ul>
     *   <li>{@code record-locator:read}  — FHIR read-only endpoints (ITI-67/66/68).
     *       Granted to clinicians and HIE consumer systems.</li>
     *   <li>{@code record-locator:write} — Ingest endpoints (/pointer-events, /consent-events).
     *       Granted only to hospital nodes and Bundle-2a consent webhook clients.</li>
     * </ul>
     *
     * <p>Keycloak puts scopes in the JWT "scope" claim as a space-separated string.
     * JwtGrantedAuthoritiesConverter reads that claim and prefixes each value with
     * "SCOPE_", so "record-locator:read" → SCOPE_record-locator:read.
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
                        // Health check — no auth required
                        .requestMatchers("/actuator/health").permitAll()
                        // FHIR read-only (ITI-67 search, ITI-66 list, ITI-68 retrieve)
                        // requires the read scope only — clinicians / consumer systems
                        .requestMatchers("/fhir/**").hasAuthority("SCOPE_record-locator:read")
                        // Ingest endpoints — hospital nodes and consent webhook only
                        // requires the write scope (which implicitly also has read)
                        .requestMatchers("/pointer-events", "/consent-events")
                                .hasAuthority("SCOPE_record-locator:write")
                        // Anything else (e.g. future endpoints) requires at least read
                        .anyRequest().hasAuthority("SCOPE_record-locator:read"))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtConverter())))
                .exceptionHandling(ex -> ex
                        // Return 401 with WWW-Authenticate: Bearer when no/invalid token
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        // Return 403 when authenticated but lacking the required scope
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler()))
                .addFilterBefore(mtlsHeaderFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Reads the "scope" claim from Keycloak JWTs and converts each space-separated
     * scope value into a Spring GrantedAuthority with the "SCOPE_" prefix.
     *
     * Example JWT scope claim:  "openid profile record-locator:read"
     * Resulting authorities:    [SCOPE_openid, SCOPE_profile, SCOPE_record-locator:read]
     *
     * The filter chain checks for SCOPE_record-locator:read or SCOPE_record-locator:write
     * depending on the endpoint, matching the scopes defined in the Keycloak realm.
     */
    @Bean
    public JwtAuthenticationConverter keycloakJwtConverter() {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        // Keycloak uses "scope" claim (the Spring default) — no override needed.
        // Authority prefix stays "SCOPE_" (the Spring default).
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
                // Write the 403 directly instead of sendError(): this filter runs before
                // authentication, and sendError() forwards to /error, which is itself
                // behind the security rules - an unauthenticated error dispatch is then
                // answered 401, hiding the real reason (missing client certificate).
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"forbidden\",\"message\":\"client certificate verification required\"}");
                return;
            }

            chain.doFilter(request, response);
        }
    }
}
