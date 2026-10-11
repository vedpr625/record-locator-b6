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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .addFilterBefore(mtlsHeaderFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Profile("dev")
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // CSRF and sessions are not meaningful for a stateless FHIR API.
                // Without disabling CSRF, Spring Security 6 rejects every FHIR
                // POST/PUT with 403 even in dev profile.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    public MtlsHeaderFilter mtlsHeaderFilter(
            @Value("${rl.security.mtls-header-enabled:false}") boolean enabled,
            @Value("${rl.security.mtls-header-name:X-Client-Cert-Verified}") String headerName) {
        return new MtlsHeaderFilter(enabled, headerName);
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

        MtlsHeaderFilter(boolean enabled, String headerName) {
            this.enabled = enabled;
            this.headerName = headerName;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                         FilterChain chain) throws ServletException, IOException {
            String path = request.getRequestURI();
            boolean isNodeToNodeEndpoint = path.equals("/consent-events") || path.equals("/pointer-events");

            if (enabled && isNodeToNodeEndpoint && request.getHeader(headerName) == null) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "client certificate verification required");
                return;
            }

            chain.doFilter(request, response);
        }
    }
}
