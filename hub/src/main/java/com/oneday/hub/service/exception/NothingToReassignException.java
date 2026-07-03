package com.oneday.hub.service.exception;

/** A FLIGHT_REASSIGNED resolved to no bagged parcels to move (unknown from-flight and no parcelIds). */
public class NothingToReassignException extends RuntimeException {
    public NothingToReassignException(String detail) {
        super("Nothing to reassign: " + detail);
    }
}
