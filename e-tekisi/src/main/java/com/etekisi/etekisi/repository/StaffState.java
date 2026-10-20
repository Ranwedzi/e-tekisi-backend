package com.etekisi.etekisi.repository;

import java.time.Instant;

/** What the token validator needs about a staff account. */
public interface StaffState {
    Instant getCredentialsChangedAt();
}
