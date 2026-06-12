package com.oneday.orders.domain.enums;

/** How a cart item was added. */
public enum CartItemSource {
    /** Added one-by-one via the booking form. */
    MANUAL,
    /** Added by an Excel bulk upload. */
    EXCEL
}
