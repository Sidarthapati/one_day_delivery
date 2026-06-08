package com.oneday.orders.service.impl;

import java.util.UUID;

/**
 * Parses authenticated user ids (carried through the service layer as strings) into UUIDs.
 * In production the id always comes from the M1 JWT principal and is a valid UUID; test
 * fixtures and the synthetic demo principal may use non-UUID strings, so parsing is lenient
 * and returns {@code null} rather than throwing.
 */
final class UserIds {

    private UserIds() {}

    /** Returns the parsed UUID, or {@code null} if {@code userId} is null/blank/not a UUID. */
    static UUID parse(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
