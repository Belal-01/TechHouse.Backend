package com.techhouse.identity.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** The signed-in user's id, for audit fields. Null outside an authenticated request (e.g. scheduled jobs). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof JwtAuthDetails details ? details.userId() : null;
    }
}
