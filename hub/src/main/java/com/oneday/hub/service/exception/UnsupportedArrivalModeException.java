package com.oneday.hub.service.exception;

import com.oneday.hub.domain.ArrivalMode;

/** Arrival mode not handled yet (AIRPORT / destination hub lands in PR #2). → 400. */
public class UnsupportedArrivalModeException extends RuntimeException {
    public UnsupportedArrivalModeException(ArrivalMode mode) {
        super("Arrival mode not supported in this release: " + mode);
    }
}
