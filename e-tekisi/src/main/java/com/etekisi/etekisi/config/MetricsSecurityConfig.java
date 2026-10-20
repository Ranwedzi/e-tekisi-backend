package com.etekisi.etekisi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code /actuator/prometheus} for the Prometheus scraper. Prometheus can't
 * mint JWTs, so when {@code tekisi.metrics.username} / {@code password} are
 * set this endpoint also accepts HTTP Basic with those credentials. Operator
 * and admin tokens keep working; without the credentials it stays
 * operator-only, as before. Caddy doesn't route it, so in the prod stack it
 * is reachable only on the internal Docker network.
 */
@Configuration
public class MetricsSecurityConfig {

    @Value("${tekisi.metrics.username:}")
    private String username;

    @Value("${tekisi.metrics.password:}")
    private String password;

    @Bean
    @Order(1)
    public SecurityFilterChain metricsFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception {
        http
                .securityMatcher("/actuator/prometheus")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAnyRole("METRICS", "OPERATOR", "ADMIN"))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));

        if (!username.isBlank() && !password.isBlank()) {
            if (password.length() < 16) {
                throw new IllegalStateException("tekisi.metrics.password (METRICS_PASSWORD) must be at least 16 characters.");
            }
            // Low BCrypt cost: this is checked on every scrape, and the password is long and random.
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(6);
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider(new InMemoryUserDetailsManager(
                    User.withUsername(username).password(encoder.encode(password)).roles("METRICS").build()));
            provider.setPasswordEncoder(encoder);
            http.authenticationProvider(provider).httpBasic(basic -> {});
        }
        return http.build();
    }
}
