package com.oneday.hub.service.exception;

import java.util.UUID;

/** A sort-plan entry or request referenced a stand that does not exist. → 404. */
public class StandNotFoundException extends RuntimeException {
    public StandNotFoundException(UUID standId) {
        super("Unknown stand: " + standId);
    }
}
