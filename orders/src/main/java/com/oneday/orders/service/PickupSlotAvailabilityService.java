package com.oneday.orders.service;

import com.oneday.orders.dto.SlotAvailabilityResponse;

/**
 * Read-only remaining capacity of DA-pickup slots for a city, so the booking UI can grey out full
 * slots. The read-side counterpart to the booking-time cap in {@code PickupSlotCapacity} — same
 * soft-cap semantics (a point-in-time count, not a reservation).
 */
public interface PickupSlotAvailabilityService {

    /** Availability for {@code city} across the next {@code days} days (from today, IST). */
    SlotAvailabilityResponse forCity(String city, int days);
}
