package com.oneday.shuttle.api;

import com.oneday.auth.security.AuthUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Shuttle endpoints: SHUTTLE_AGENT acting as themselves (ADMIN may act for anyone). City from the principal. */
final class ShuttleAuthz {

    private ShuttleAuthz() {
    }

    static UUID requireAgent(AuthUserDetails principal, UUID agentId) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String role = principal.getUser().getRole().getName();
        boolean admin = "ADMIN".equals(role);
        if (!admin && !"SHUTTLE_AGENT".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Shuttle agents only");
        }
        if (!admin && !principal.getUserId().equals(agentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only act as yourself");
        }
        return principal.getUserId();
    }

    static String city(AuthUserDetails principal) {
        return principal.getUser().getCityId();
    }
}
