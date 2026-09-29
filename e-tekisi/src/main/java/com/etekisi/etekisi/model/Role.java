package com.etekisi.etekisi.model;

/**
 * Principal roles carried in the JWT {@code role} claim. Stored as strings,
 * so the values must never be renamed.
 */
public enum Role {
    COMMUTER,
    DRIVER,
    OPERATOR,
    ADMIN;

    public boolean isStaff() {
        return this == OPERATOR || this == ADMIN;
    }
}
