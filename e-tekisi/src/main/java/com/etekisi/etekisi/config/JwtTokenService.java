package com.etekisi.etekisi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Issues short-lived HS256 JWTs for authenticated commuters / drivers /
 * operators. Called from {@code RegistrationController.login}. Tokens carry
 * a single {@code role} claim (consumed by {@link
 * SecurityConfig#jwtAuthenticationConverter()}) plus the userId in
 * {@code sub}. Deliberately simple — no refresh tokens yet; rotation can be
 * layered in later without a breaking change.
 */
@Service
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final String issuer;
    private final long expiryMinutes;

    public JwtTokenService(JwtEncoder encoder,
                           @Value("${tekisi.security.jwt.issuer}") String issuer,
                           @Value("${tekisi.security.jwt.expiry-minutes}") long expiryMinutes) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.expiryMinutes = expiryMinutes;
    }

    public String mint(Long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(expiryMinutes)))
                .subject(String.valueOf(userId))
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long expirySeconds() {
        return expiryMinutes * 60L;
    }

    /** The body of a successful sign-in, as the frontend expects it. */
    public Map<String, Object> loginBody(Long userId, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "SUCCESS");
        body.put("userId", userId);
        body.put("role", role);
        body.put("token", mint(userId, role));
        body.put("tokenType", "Bearer");
        body.put("expiresInSeconds", expirySeconds());
        return body;
    }
}
