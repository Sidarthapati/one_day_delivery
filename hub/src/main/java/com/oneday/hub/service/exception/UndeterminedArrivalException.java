package com.oneday.hub.service.exception;

import com.oneday.common.domain.enums.ShipmentState;

/** The parcel is not in a dock-arrival state, so the arrival mode can't be derived (§6). → 409. */
public class UndeterminedArrivalException extends RuntimeException {
    public UndeterminedArrivalException(ShipmentState state) {
        super("Parcel is not in a dock-arrival state, cannot derive arrival mode: " + state);
    }
}
