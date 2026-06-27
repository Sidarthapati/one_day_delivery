package com.oneday.hub.service.exception;

import java.util.UUID;

/** No flight bag with this id. → 404. */
public class BagNotFoundException extends RuntimeException {
    public BagNotFoundException(UUID bagId) {
        super("Unknown bag: " + bagId);
    }
}
