package com.oneday.hub.service.exception;

/** The parcel is already in an open bag. → 409. */
public class DuplicateBagItemException extends RuntimeException {
    public DuplicateBagItemException(String shipmentRef) {
        super("Parcel already in a bag: " + shipmentRef);
    }
}
