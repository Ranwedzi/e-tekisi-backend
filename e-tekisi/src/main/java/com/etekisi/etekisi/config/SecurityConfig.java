package com.etekisi.etekisi.config;

import com.etekisi.etekisi.security.DeletedAccountTokenValidator;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
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
 * Security configuration: every endpoint requires a valid HS256 JWT in the
 * {@code Authorization: Bearer …} header, except the explicit public
 * allow-list (auth, actuator/health, OpenAPI docs).
 *
 * <p>Two layers:
 * <ul>
 *   <li>Path matchers below gate each area by role (coarse).</li>
 *   <li>Controllers check ownership through {@code CurrentUser}: a caller can
 *       only read or change their own bookings, wallet, notifications, etc.
 *       (fine-grained).</li>
 * </ul>
 *
 * <p>There is no permit-all mode any more. Tests authenticate with
 * {@code SecurityMockMvcRequestPostProcessors.jwt()}.
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
     * Role hierarchy:
     * <ul>
     *   <li>commuter — books, pays, chats, receives notifications.</li>
     *   <li>driver   — accepts/completes trips, posts location, joins queues, cashes out.</li>
     *   <li>operator — observes the agent layer and operational insights.</li>
     *   <li>admin    — superset, used for support tooling.</li>
     * </ul>
     */
    @Bean
    public SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API; no cookies, so CSRF is moot.
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
                        // Panic button: any signed-in trip participant (checked in the controller).
                        .requestMatchers(HttpMethod.POST, "/api/agents/safety/panic").authenticated()
                        // Agent surface — operators only (read-only insight + dispatch).
                        .requestMatchers("/api/agents/**", "/api/insights/**", "/api/ops/**")
                            .hasAnyRole("OPERATOR", "ADMIN")
                        // Driver areas. Controllers additionally check the driver is acting as themselves.
                        .requestMatchers("/api/payouts/**").hasAnyRole("DRIVER", "OPERATOR", "ADMIN")
                        .requestMatchers("/api/bookings/pending",
                                         "/api/bookings/*/offer",
                                         "/api/bookings/*/complete/**",
                                         "/api/bookings/*/decline/**",
                                         "/api/locations/update",
                                         "/api/queue/**")
                            .hasAnyRole("DRIVER", "OPERATOR", "ADMIN")
                        // Commuter areas.
                        .requestMatchers("/api/wallet/**", "/api/saved-ranks/**", "/api/bookings/create",
                                         "/api/bookings/*/accept-offer", "/api/bookings/*/decline-offer")
                            .hasAnyRole("COMMUTER", "OPERATOR", "ADMIN")
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
    public JwtDecoder jwtDecoder(DeletedAccountTokenValidator deletedAccounts) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), deletedAccounts));
        return decoder;
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
