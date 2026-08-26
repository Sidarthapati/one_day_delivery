package com.oneday.orders.repository;

import java.time.Instant;

/** Projection for the pickup-slot availability rollup: booked count per slot start instant. */
public interface SlotBookingCount {
    Instant getStart();
    long getCount();
}
