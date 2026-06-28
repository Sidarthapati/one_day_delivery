package com.oneday.hub.service.exception;

/** The scanned parcel ref is unknown to M4. → 404. */
public class ParcelNotFoundException extends RuntimeException {
    public ParcelNotFoundException(String shipmentRef) {
        super("Unknown parcel ref: " + shipmentRef);
    }
}
