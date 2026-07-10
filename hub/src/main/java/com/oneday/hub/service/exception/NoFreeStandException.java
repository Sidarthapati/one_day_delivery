package com.oneday.hub.service.exception;

import java.util.UUID;

/**
 * No free flight-bag stand in the hub's pool to open a new bag on — every stand already holds an
 * open bag. This is a hub-overload condition: escalate, never silently drop (C1, C11). → 409.
 * Real back-pressure handling lands in PR #3 (§11).
 */
public class NoFreeStandException extends RuntimeException {
    public NoFreeStandException(UUID hubId) {
        super("No free flight-bag stand available at hub " + hubId);
    }
}
