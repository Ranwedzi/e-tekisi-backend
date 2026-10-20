package com.etekisi.etekisi.repository;

import java.time.Instant;

/** What the token validator needs to know about an account on every request. */
public interface AccountState {
    Instant getDeletedAt();
    Instant getCredentialsChangedAt();
}
