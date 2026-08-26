package com.oneday.orders.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Remaining pickup-slot capacity for a city over the next few days, so the Ship form can hide/disable
 * full slots before booking. {@code remaining} is {@code maxPerSlot - booked} (never negative);
 * {@code full} is {@code remaining == 0}. Only DA_PICKUP slots are capped (SELF_DROP is uncapped).
 */
public record SlotAvailabilityResponse(String city, List<Slot> slots) {

    /** @param startHour IST slot start hour (7/9/11/13/15/17/19); the slot runs 2 hours. */
    public record Slot(LocalDate date, int startHour, int remaining, boolean full) {}
}
