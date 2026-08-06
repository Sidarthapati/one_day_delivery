package com.oneday.orders.domain;

/** Lifecycle of a single COD collection (money owed to a vendor for one delivered parcel). */
public enum CodCollectionState {
    /** Booked; the buyer has not yet paid (parcel not delivered). */
    AWAITING_COLLECTION,
    /** Delivered — the DA collected the cash; owed to the vendor, not yet paid out. */
    COLLECTED,
    /** Paid out to the vendor in a remittance batch. */
    REMITTED,
    /** Shipment cancelled / returned before delivery — nothing collected. */
    CANCELLED
}
