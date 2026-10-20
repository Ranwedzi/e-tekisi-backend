package com.etekisi.etekisi.security;

import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Rejects tokens that belong to a deleted account, so deleting an account
 * signs it out everywhere at once instead of when its token expires. One
 * primary-key lookup per request; staff tokens aren't checked.
 */
@Component
public class DeletedAccountTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error DELETED =
            new OAuth2Error("invalid_token", "This account has been deleted.", null);

    private final UserRepository users;
    private final DriverRepository drivers;

    public DeletedAccountTokenValidator(UserRepository users, DriverRepository drivers) {
        this.users = users;
        this.drivers = drivers;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Long id;
        try {
            id = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            return OAuth2TokenValidatorResult.success(); // CurrentUser rejects it later.
        }
        boolean deleted = switch (String.valueOf(jwt.getClaimAsString("role"))) {
            case "COMMUTER" -> users.existsByIdAndDeletedAtIsNotNull(id);
            case "DRIVER" -> drivers.existsByIdAndDeletedAtIsNotNull(id);
            default -> false;
        };
        return deleted ? OAuth2TokenValidatorResult.failure(DELETED) : OAuth2TokenValidatorResult.success();
    }
}
