package com.oneday.airline.service.exception;

import java.util.UUID;

/** A ground-crew confirmation referenced an AWB that doesn't exist. → 404. */
public class AwbNotFoundException extends RuntimeException {
    public AwbNotFoundException(UUID awbId) {
        super("Unknown AWB: " + awbId);
    }
}
