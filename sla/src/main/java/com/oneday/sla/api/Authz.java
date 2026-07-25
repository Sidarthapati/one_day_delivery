package com.oneday.sla.api;

import com.oneday.auth.security.AuthUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Auth helpers for the SLA control-tower endpoints. Identity + role come from the authenticated
 * principal (M1 JWT). Mirrors {@code orders.api.Authz}.
 */
final class Authz {

    static final String ADMIN = "ADMIN";
    static final String STATION_MANAGER = "STATION_MANAGER";
    static final String SUPERVISOR = "SUPERVISOR";

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
                "Role " + role + " is not permitted to view SLA data");
    }

    /**
     * The city an ops query is scoped to: {@code null} for ADMIN (sees every city), else the station
     * manager / supervisor's own city (403 if they have none assigned).
     */
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
