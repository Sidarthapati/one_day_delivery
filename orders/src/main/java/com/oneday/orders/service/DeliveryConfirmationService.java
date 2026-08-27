package com.oneday.orders.service;

import java.util.UUID;

/**
 * Receiver accept/reject on an incoming delivery. When the parcel's flight departs (or, same-city, it's
 * sorted for delivery) the receiver is emailed an ETD + a no-login accept/reject link. Silence = accept;
 * an explicit reject re-parks the delivery for the receiver's chosen next-day shift (a courtesy
 * reschedule, NOT a failed attempt).
 */
public interface DeliveryConfirmationService {

    /**
     * Prompt the receiver (best-effort, idempotent). Loads the shipment, computes the ETD from flight
     * timings + hub/last-mile windows, mints a one-time link token, and enqueues the email. No-op if the
     * receiver has no email or a live prompt already exists; never throws into the caller.
     */
    void promptOnDeparture(UUID shipmentId);

    /** Public-link summary for the landing page (opaque token). 404 if the token is unknown. */
    DeliveryConfirmationView getByToken(String token);

    /** Receiver accepted (or the link was opened and confirmed). Idempotent once already responded. */
    DeliveryConfirmationView accept(String token);

    /**
     * Receiver rejected today's delivery and picked {@code targetShift} (SHIFT_1 | SHIFT_2) for next day.
     * Publishes {@code ReceiverRejectedEvent} so M5 re-parks the delivery. Idempotent once responded.
     */
    DeliveryConfirmationView reject(String token, String targetShift);

    /** Flip stale PENDING prompts to EXPIRED (cosmetic — silence already counts as accept). Returns count. */
    int expireStale();
}
