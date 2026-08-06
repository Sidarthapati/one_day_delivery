package com.oneday.airline.service;

import com.oneday.airline.domain.Awb;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Books a hub's sealed flight bag onto a flight as one confirmed reservation (§6). Triggered off M7's
 * BAG_SEALED notification.
 */
public interface AwbBookingService {

    /**
     * Idempotent on {@code bagId} — a redelivered BAG_SEALED notification (§11) returns the existing
     * booking rather than booking twice.
     */
    Awb book(BookBagCommand command);

    record BookBagCommand(UUID bagId, String flightNo, LocalDate flightDate, int parcelCount, int weightGrams) {
    }
}
