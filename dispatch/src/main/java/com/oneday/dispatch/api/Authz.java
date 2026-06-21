package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Authn/authz helpers for the dispatch controllers. Identity and role come from the authenticated
 * principal (M1 JWT in prod; the synthetic demo principal under {@code !prod}) — never a client header.
 */
final class Authz {

    static final String ADMIN = "ADMIN";
    static final String DELIVERY_ASSOCIATE = "DELIVERY_ASSOCIATE";
    static final String STATION_MANAGER = "STATION_MANAGER";

    private Authz() {}

    /** The caller's role name, or 401 if unauthenticated. */
    static String requireRole(AuthUserDetails principal, String... allowedRoles) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String role = principal.getUser().getRole().getName();
        if (ADMIN.equals(role)) {
            return role;
        }
        for (String allowed : allowedRoles) {
            if (allowed.equals(role)) {
                return role;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Role " + role + " is not permitted to perform this action");
    }

    static boolean isAdmin(AuthUserDetails principal) {
        return principal != null && ADMIN.equals(principal.getUser().getRole().getName());
    }

    /** The caller's user id, or 401 if unauthenticated. */
    static UUID requireUserId(AuthUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return principal.getUserId();
    }

    /**
     * A DA may act only on their own queue: the caller must be the {@code DELIVERY_ASSOCIATE} whose
     * user id equals {@code daId}. ADMIN is allowed for any DA (ops override).
     *
     * @throws ResponseStatusException 401 if unauthenticated, 403 otherwise
     */
    static void requireDaSelf(AuthUserDetails principal, UUID daId) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String role = principal.getUser().getRole().getName();
        if (ADMIN.equals(role)) {
            return;
        }
        if (!DELIVERY_ASSOCIATE.equals(role) || !principal.getUserId().equals(daId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the delivery associate may act on their own tasks");
        }
    }
}
