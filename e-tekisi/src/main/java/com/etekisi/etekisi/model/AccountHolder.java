package com.etekisi.etekisi.model;

import java.time.Instant;

/**
 * What commuters ({@link User}) and drivers ({@link Driver}) have in common
 * for sign-in, verification and consent. They live in separate tables, so
 * account-security code works through this instead of duplicating itself.
 */
public interface AccountHolder {
    Long getId();
    String getFullName();
    String getEmail();
    String getPhoneNumber();
    String getPassword();
    void setPassword(String bcryptHash);

    Instant getEmailVerifiedAt();
    void setEmailVerifiedAt(Instant at);
    Instant getPhoneVerifiedAt();
    void setPhoneVerifiedAt(Instant at);

    String getTermsVersion();
    void setTermsVersion(String version);
    Instant getTermsAcceptedAt();
    void setTermsAcceptedAt(Instant at);

    /** Tokens issued before this are rejected (password reset or change). */
    Instant getCredentialsChangedAt();
    void setCredentialsChangedAt(Instant at);

    Instant getDeletedAt();

    /** A verified email address or phone number. */
    default boolean isVerified() {
        return getEmailVerifiedAt() != null || getPhoneVerifiedAt() != null;
    }
}
