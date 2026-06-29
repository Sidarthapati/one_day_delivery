package com.oneday.common.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read M5's live pickup queue (which DA is carrying which parcel) without importing the dispatch module.
 * M6's "run the day" demo uses it to drive the van↔DA handoff from <b>real</b> dispatch decisions: every
 * queued pickup becomes a {@code DaParcelPickedUp} the van comes to collect, so the on-map "collected N"
 * at each cron meeting equals exactly what M5 assigned to that DA. Implemented in dispatch, consumed in
 * routing — both depend only on {@code common}, like the other cross-module ports.
 */
public interface DaPickupQueuePort {

    /** One parcel M5 has placed on a DA's queue for the day (pickup or delivery). */
    record QueuedPickup(UUID daId, UUID shipmentId, UUID tileId) {}

    /** Active (QUEUED or in-progress) <b>pickups</b> for the city/day, one entry per parcel. */
    List<QueuedPickup> queuedPickups(UUID cityId, LocalDate date);

    /**
     * Pickups the DA has actually <b>collected</b> (OTP-verified at the door → task IN_PROGRESS), i.e.
     * ready for the van to take. The van must only carry these — a parcel with no customer OTP has not
     * been picked up. M6's run uses this (not {@link #queuedPickups}) so the on-map handoff never collects
     * an un-verified parcel; un-collected pickups are simply left behind (next loop / deferred).
     */
    List<QueuedPickup> pickedUpPickups(UUID cityId, LocalDate date);

    /** Active (QUEUED or in-progress) <b>deliveries</b> for the city/day, one entry per parcel. */
    List<QueuedPickup> queuedDeliveries(UUID cityId, LocalDate date);
}
