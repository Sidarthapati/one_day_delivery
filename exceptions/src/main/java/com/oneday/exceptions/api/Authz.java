package com.oneday.exceptions.api;

import com.oneday.auth.security.AuthUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Auth helpers for the M11 exceptions endpoints. Identity + role come from the M1 JWT principal.
 * Mirrors {@code sla.api.Authz}.
 */
final class Authz {

    static final String ADMIN = "ADMIN";
    static final String STATION_MANAGER = "STATION_MANAGER";
    static final String SUPERVISOR = "SUPERVISOR";
    static final String CALL_CENTER_AGENT = "CALL_CENTER_AGENT";

    private Authz() {}

    static String requireUserId(AuthUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return principal.getUserId().toString();
    }

    static String role(AuthUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return principal.getUser().getRole().getName();
    }

    /** Authorize against {@code allowedRoles}; ADMIN is always allowed. */
    static void requireRole(AuthUserDetails principal, String... allowedRoles) {
        String role = role(principal);
        if (ADMIN.equals(role)) {
            return;
        }
        for (String allowed : allowedRoles) {
            if (allowed.equals(role)) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Role " + role + " is not permitted to view exceptions");
    }

    /** The city an ops query is scoped to: {@code null} for ADMIN, else the user's own city (403 if none). */
    static String cityScope(AuthUserDetails principal) {
        String role = role(principal);
        if (ADMIN.equals(role)) {
            return null;
        }
        String city = principal.getUser().getCityId();
        if (city == null || city.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No city assigned to this user");
        }
        return city;
    }
}
