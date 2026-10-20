package com.etekisi.etekisi.security;

import com.etekisi.etekisi.web.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Password rules for sign-up, reset and change, following NIST SP 800-63B:
 * long enough, not a well-known password, not built from the person's own
 * email or name. No composition rules (they make passwords worse, not better).
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 200;

    private final Set<String> common;

    public PasswordPolicy() {
        try (InputStream in = new ClassPathResource("security/common-passwords.txt").getInputStream()) {
            common = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new IllegalStateException("security/common-passwords.txt is missing", e);
        }
    }

    /** Throws 400 WEAK_PASSWORD with a reason the person can act on. */
    public void check(String password, String email, String fullName) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw weak("Use at least " + MIN_LENGTH + " characters. A few unrelated words make a good password.");
        }
        if (password.length() > MAX_LENGTH) {
            throw weak("Use at most " + MAX_LENGTH + " characters.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (common.contains(lower) || lower.chars().distinct().count() <= 2) {
            throw weak("That password is too common. Pick something less predictable.");
        }
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).split("@", 2)[0];
            if (local.length() >= 4 && lower.contains(local)) {
                throw weak("Don't use your email address in your password.");
            }
        }
        if (fullName != null) {
            for (String part : fullName.toLowerCase(Locale.ROOT).split("\\s+")) {
                if (part.length() >= 4 && lower.contains(part)) {
                    throw weak("Don't use your name in your password.");
                }
            }
        }
    }

    private static ApiException weak(String detail) {
        return ApiException.badRequest("WEAK_PASSWORD", detail);
    }
}
