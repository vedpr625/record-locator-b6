package lk.gov.moh.dhp.rl.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;

/**
 * Provides a lightweight JwtDecoder AND a test SecurityFilterChain for
 * {@code @WebMvcTest} slices.
 *
 * <p>Two problems this config solves:</p>
 * <ol>
 *   <li><strong>Missing JwtDecoder</strong> — Spring Boot's JWT auto-configuration
 *       requires either a jwk-set-uri or an issuer-uri.  In the test profile both
 *       are empty to avoid network calls, so the auto-configured decoder fails.
 *       This config provides an HMAC-based decoder; in practice MockMvc's
 *       {@code .with(jwt())} post-processor bypasses signature verification
 *       entirely, so the key value doesn't matter.</li>
 *   <li><strong>401 vs 403 on unauthenticated requests</strong> — Spring Security's
 *       default entry point for {@code @WebMvcTest} returns 403 for anonymous POST
 *       requests (CSRF protection).  The production app is a stateless JWT API with
 *       no CSRF tokens; unauthenticated requests must return 401 with a
 *       {@code WWW-Authenticate: Bearer} header.  This config replicates the
 *       production security rules for test slices so the controller tests exercise
 *       the same security behaviour as production.</li>
 * </ol>
 *
 * <p>The production {@code SecurityConfig.productionFilterChain} bean is still
 * loaded by {@code @WebMvcTest} (Spring Boot includes all {@code @Configuration}
 * security beans).  This test config bean has a <em>higher</em> order so it takes
 * precedence; alternatively, both would conflict if they both try to register a
 * {@code SecurityFilterChain} for the same request matcher — but since this is a
 * {@code @TestConfiguration} its bean definitions override production beans of the
 * same type.  (Spring replaces matching bean names; the bean name here is
 * {@code testSecurityFilterChain} which is distinct from
 * {@code productionFilterChain}.)</p>
 */
@TestConfiguration
public class TestSecurityConfig {

    /**
     * HMAC JwtDecoder — the actual key is irrelevant because MockMvc's
     * {@code .with(jwt())} post-processor injects a pre-authenticated token
     * that never goes through signature verification.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] key = "test-secret-key-for-unit-tests-only".getBytes();
        SecretKeySpec keySpec = new SecretKeySpec(key, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(keySpec).build();
    }

    /**
     * Test SecurityFilterChain that mirrors the production rules but is safe for
     * {@code @WebMvcTest}: no CSRF, stateless sessions, JWT resource server with
     * the correct 401 entry point for unauthenticated requests, and no mTLS filter
     * (there is no embedded reverse proxy in tests).
     */
    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler()));
        return http.build();
    }
}
