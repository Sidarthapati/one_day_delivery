package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Small authentication/authorization helpers shared by the orders controllers.
 * Identity and role come from the authenticated principal (M1 JWT in prod; the synthetic
 * demo principal under {@code !prod}) — never from a client-supplied header.
 */
final class Authz {

    static final String ADMIN = "ADMIN";

    private Authz() {}

    /** The caller's user id, or 401 if unauthenticated. */
    static String requireUserId(AuthUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return principal.getUserId().toString();
    }

    /**
     * Authorizes the caller against {@code allowedRoles}. ADMIN is always allowed.
     * @throws ResponseStatusException 401 if unauthenticated, 403 if the role is not permitted
     */
    static void requireRole(AuthUserDetails principal, String... allowedRoles) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String role = principal.getUser().getRole().getName();
        if (ADMIN.equals(role)) {
            return;
        }
        for (String allowed : allowedRoles) {
            if (allowed.equals(role)) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Role " + role + " is not permitted to perform this action");
    }

    /**
     * Authorizes the caller against {@code allowedRoles} <b>without</b> the ADMIN bypass.
     * For actions reserved to customer accounts (booking): an ADMIN reads the orders database
     * but must not be able to place an order. ADMIN therefore gets 403 here unless it is itself
     * one of {@code allowedRoles}.
     *
     * @throws ResponseStatusException 401 if unauthenticated, 403 if the role is not permitted
     */
    static void requireCustomerRole(AuthUserDetails principal, String... allowedRoles) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String role = principal.getUser().getRole().getName();
        for (String allowed : allowedRoles) {
            if (allowed.equals(role)) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Role " + role + " is not permitted to book shipments");
    }
}
