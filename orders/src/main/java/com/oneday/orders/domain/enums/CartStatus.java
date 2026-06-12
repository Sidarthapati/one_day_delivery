package com.oneday.orders.domain.enums;

/** Lifecycle of a shipment cart. */
public enum CartStatus {
    /** Accepting items; the user's single active cart. */
    OPEN,
    /** All items booked; the cart is closed. */
    CHECKED_OUT,
    /** Discarded without booking. */
    ABANDONED
}
