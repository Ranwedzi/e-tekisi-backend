package com.etekisi.etekisi.security;

import com.etekisi.etekisi.repository.AccountState;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Rejects tokens of deleted accounts, and tokens issued before the account's
 * password was last reset or changed. Deleting an account or changing a
 * password therefore signs it out everywhere at once instead of when its
 * tokens expire. One primary-key lookup per request; staff tokens aren't
 * checked.
 */
@Component
public class AccountStateTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error DELETED =
            new OAuth2Error("invalid_token", "This account has been deleted.", null);
    private static final OAuth2Error STALE =
            new OAuth2Error("invalid_token", "The password was changed; please sign in again.", null);

    private final UserRepository users;
    private final DriverRepository drivers;

    public AccountStateTokenValidator(UserRepository users, DriverRepository drivers) {
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
        Optional<AccountState> state = switch (String.valueOf(jwt.getClaimAsString("role"))) {
            case "COMMUTER" -> users.findStateById(id);
            case "DRIVER" -> drivers.findStateById(id);
            default -> Optional.empty();
        };
        if (state.isEmpty()) return OAuth2TokenValidatorResult.success();
        if (state.get().getDeletedAt() != null) return OAuth2TokenValidatorResult.failure(DELETED);

        Instant changed = state.get().getCredentialsChangedAt();
        Instant issued = jwt.getIssuedAt();
        // iat has whole-second precision, so compare at that precision: a token
        // minted in the same second as the change is the new sign-in.
        if (changed != null && issued != null && issued.isBefore(changed.truncatedTo(ChronoUnit.SECONDS))) {
            return OAuth2TokenValidatorResult.failure(STALE);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
