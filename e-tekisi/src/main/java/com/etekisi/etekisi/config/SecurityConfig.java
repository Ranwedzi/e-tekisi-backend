package com.etekisi.etekisi.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Two-mode security configuration.
 *
 * <p><b>jwt mode</b> (production default, enforced when
 * {@code tekisi.security.mode=jwt}): every endpoint requires a valid HS256
 * JWT in the {@code Authorization: Bearer …} header, except the explicit
 * public allow-list (auth, actuator/health, swagger if added later).
 * Role-based access is enforced via {@link
 * org.springframework.security.access.prepost.PreAuthorize} on controller
 * methods AND via path matchers below as defense-in-depth.
 *
 * <p><b>permit-all mode</b> (used by integration tests, set via
 * {@code tekisi.security.mode=permit-all} in {@code src/test/resources/
 * application.properties}): keeps the legacy wide-open chain so existing
 * MockMvc tests don't have to mint JWTs. CORS rules are still applied.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // turns on @PreAuthorize / @PostAuthorize at runtime.
public class SecurityConfig {

    @Value("${tekisi.security.jwt.secret}")
    private String jwtSecret;

    @Value("${tekisi.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Production / default chain. Active unless {@code tekisi.security.mode}
     * is explicitly set to {@code permit-all}. Models the role hierarchy:
     * <ul>
     *   <li>commuter — books, pays, receives notifications.</li>
     *   <li>driver   — accepts/completes trips, posts location, joins queues.</li>
     *   <li>operator — observes the agent layer and operational insights.</li>
     *   <li>admin    — superset, used for support tooling.</li>
     * </ul>
     */
    @Bean
    @ConditionalOnProperty(name = "tekisi.security.mode", havingValue = "jwt", matchIfMissing = true)
    public SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless API; CSRF is moot.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints — minimum surface for clients to bootstrap.
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register/**",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/error",
                                // OpenAPI / Swagger UI for the frontend.
                                "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**"
                        ).permitAll()
                        // Operator-only observability endpoints.
                        .requestMatchers("/actuator/**").hasAnyRole("OPERATOR", "ADMIN")
                        // Agent surface — operators only (read-only insight + dispatch).
                        .requestMatchers("/api/agents/**", "/api/insights/**", "/api/payouts/**")
                            .hasAnyRole("OPERATOR", "ADMIN")
                        // Driver-only writes — accept/complete/decline/queue/location.
                        .requestMatchers("/api/bookings/*/accept/**",
                                         "/api/bookings/*/complete/**",
                                         "/api/bookings/*/decline/**",
                                         "/api/locations/driver/**",
                                         "/api/queue/**")
                            .hasAnyRole("DRIVER", "OPERATOR", "ADMIN")
                        // Default: any authenticated principal can hit the rest of /api/**.
                        .requestMatchers("/api/**").authenticated()
                        // Anything else (e.g. static error pages) — deny.
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    /**
     * Test / legacy chain. Active only when {@code tekisi.security.mode=permit-all}.
     * Mirrors the prior wide-open behaviour so existing MockMvc tests keep
     * passing while the prod profile enforces JWT.
     */
    @Bean
    @ConditionalOnProperty(name = "tekisi.security.mode", havingValue = "permit-all")
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * CORS source. Reads the comma-separated allow-list from
     * {@code tekisi.cors.allowed-origins}; in prod this MUST be enumerated
     * (no wildcards). {@code allowedOriginPatterns} is used so we can keep
     * {@code allowCredentials=true}.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        cfg.setAllowedOriginPatterns(origins);
        cfg.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        cfg.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "X-Requested-With",
                "X-Correlation-Id", "Idempotency-Key"));
        cfg.setExposedHeaders(Arrays.asList("X-Correlation-Id"));
        cfg.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    // ---------------------------------------------------------------- JWT --

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder
                .withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    private SecretKeySpec secretKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Fail-fast at startup rather than letting JwtEncoder throw on the
            // first request in prod. 32 bytes = 256 bits = HS256 minimum.
            throw new IllegalStateException(
                "tekisi.security.jwt.secret must be at least 32 bytes (HS256 minimum). " +
                "Got " + keyBytes.length + " bytes. Set JWT_SIGNING_SECRET to a strong value.");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /**
     * Maps the {@code role} claim on a JWT (e.g. "DRIVER") to a
     * Spring Security authority ({@code ROLE_DRIVER}) so {@code hasRole(...)}
     * matchers work without us writing a custom converter at every callsite.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName("role");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        // Use the userId (sub) as the principal name.
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}
