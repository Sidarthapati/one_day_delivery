package com.oneday.dispatch.service;

import java.time.Instant;
import java.util.UUID;

/** Holds scheduled/off-hours pickups out of the DA queue until they're due, then releases them. */
public interface ScheduledPickupService {

    /**
     * Decide whether an incoming pickup should wait. If it has a future slot (or is an ASAP order booked
     * off-hours) a HELD row is created and {@code true} is returned; otherwise it is due now and
     * {@code false} is returned so the caller assigns immediately. {@code slotStart}/{@code slotEnd} are
     * null for ASAP orders.
     */
    boolean holdIfNotDue(UUID shipmentId, UUID cityId, UUID tileId, double lat, double lon,
                         String paymentMode, Instant slotStart, Instant slotEnd);

    /** Cancel a live HELD hold for a shipment (it was cancelled while waiting). No-op if none. */
    void cancel(UUID shipmentId);

    /** Release every HELD hold now due by assigning it through the normal pipeline. Returns the count. */
    int releaseDue();
}
