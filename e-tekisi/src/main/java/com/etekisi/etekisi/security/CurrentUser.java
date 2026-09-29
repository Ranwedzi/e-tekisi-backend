package com.etekisi.etekisi.security;

import com.etekisi.etekisi.model.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The authenticated caller, read from the validated JWT. Controllers use this
 * instead of trusting ids from the path, query string or body: {@code sub} is
 * the user id and {@code role} says which table that id belongs to.
 */
@Component
public class CurrentUser {

    public Long id() {
        String sub = jwt().getSubject();
        try {
            return Long.valueOf(sub);
        } catch (NumberFormatException e) {
            throw new AuthenticationCredentialsNotFoundException("Token subject is not a user id");
        }
    }

    public Role role() {
        String claim = jwt().getClaimAsString("role");
        try {
            return Role.valueOf(claim);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AuthenticationCredentialsNotFoundException("Token has no valid role");
        }
    }

    public boolean isStaff() {
        return role().isStaff();
    }

    /** Passes if the caller is {@code (id, role)} or is staff; otherwise 403. */
    public void requireSelf(Long id, Role expectedRole) {
        if (isStaff()) return;
        if (role() != expectedRole || !id().equals(id)) {
            throw new AccessDeniedException("Not your resource");
        }
    }

    /** Returns the caller's id if their role is {@code expected}; otherwise 403. */
    public Long requireRole(Role expected) {
        if (role() != expected) {
            throw new AccessDeniedException("Requires role " + expected);
        }
        return id();
    }

    private Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        throw new AuthenticationCredentialsNotFoundException("A bearer token is required");
    }
}
