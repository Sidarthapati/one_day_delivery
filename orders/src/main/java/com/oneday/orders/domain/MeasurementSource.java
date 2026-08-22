package com.oneday.orders.domain;

/**
 * Who/what produced a parcel-dimension observation. Extensible — new sources append without a
 * schema change. The customer's declared dimensions live on {@code Shipment} and are never mutated;
 * these are additional observations of the same parcel.
 */
public enum MeasurementSource {
    /** What the merchant declared at booking (kept on Shipment; a row here is optional/for parity). */
    CUSTOMER_DECLARED,
    /** Measured by the delivery associate's phone at first-mile pickup (this feature). */
    DA_PICKUP,
    /** Measured at the hub (future). */
    HUB
}
