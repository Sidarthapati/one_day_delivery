package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.DeferReason;
import com.oneday.dispatch.domain.TaskType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The assignment engine: places an incoming pickup/delivery onto the cheapest-feasible DA queue, or
 * defers it for retry. Selection picks the least-loaded DA serving the task's tile, gates on the
 * cron-meeting hard constraint via {@link CronFeasibilityService}, and falls back to cross-territory
 * spill-over (design §10) before deferring. Every decision is recorded in {@code da_assignment_audit}.
 */
public interface DispatchService {

    /**
     * Assign a first-mile pickup. {@code originTileId} may be null (resolved from {@code lat/lon} via
     * M3); {@code paymentMode} is stored for COD-aware handling and may be null. {@code orderId}/
     * {@code orderRef} are the parent order (M4 Order → N shipments), denormalised onto the task so the
     * DA app can group same-order/same-location tasks; both null for legacy/pre-Order shipments.
     */
    AssignmentResult assignPickup(UUID shipmentId, UUID cityId, double lat, double lon,
                                  UUID originTileId, String paymentMode, UUID orderId, String orderRef);

    /**
     * Assign a last-mile delivery. {@code destTileId} may be null (resolved from {@code lat/lon}).
     * {@code orderId}/{@code orderRef} carry the parent order for stop grouping (both may be null).
     */
    AssignmentResult assignDelivery(UUID shipmentId, UUID cityId, double lat, double lon, UUID destTileId,
                                    UUID orderId, String orderRef);

    /**
     * Cancel a shipment's active task and resequence the DA's queue. A QUEUED task is simply removed.
     * An IN_PROGRESS task (DA already holds the parcel) is also removed from the DA's active load — the
     * cancelled parcel is no longer a hub-bound pickup, so it must stop consuming the DA's cron budget;
     * the physical parcel becomes a return (RTO) handled by M11. No active task → no-op (idempotent).
     */
    void cancelTask(UUID shipmentId, TaskType taskType);

    /** Re-attempt a previously deferred dispatch; flips it to ASSIGNED on success, leaves it PENDING otherwise. */
    AssignmentResult reassignDeferred(UUID deferredId);

    /**
     * Manually assign a PENDING deferred pickup/delivery to a specific DA — a station manager's
     * override of the automatic cheapest-least-loaded pick. The cron-meeting hard constraint still
     * applies exactly as it does for an automated assignment: if {@code daId} can't make it, the
     * task stays PENDING and the result comes back {@code DEFERRED}, not an error — a rejection the
     * caller can show, not a silent no-op. Recorded in {@code da_assignment_audit} as
     * {@code MANUAL_ASSIGNED} so it's distinguishable from an algorithmic pick.
     *
     * @throws IllegalArgumentException if {@code deferredId} doesn't exist
     * @throws IllegalStateException    if {@code daId} isn't currently assignable (off-shift/absent)
     */
    AssignmentResult assignDeferredToDa(UUID deferredId, UUID daId);

    /**
     * Manually escalate a PENDING deferred task to M11 right now, instead of waiting for
     * {@code DeferredRetryJob}'s max-retries cap. No-op if the row is no longer PENDING (already
     * assigned or previously escalated).
     *
     * @throws IllegalArgumentException if {@code deferredId} doesn't exist
     */
    void escalateDeferred(UUID deferredId);

    /**
     * Park a last-mile delivery for a future day/shift retry (redelivery). Writes a PENDING
     * {@code deferred_dispatch} row dated {@code targetDate} and tagged {@code targetShift} (SHIFT_1 /
     * SHIFT_2, or null for any shift), so {@code DeferredRetryJob} re-assigns it to the tile owner on
     * that day/shift. Used by the receiver-reject handler and an ops delivery reschedule. Idempotent:
     * a shipment that already has an active DELIVERY task or a PENDING delivery deferral is a no-op.
     */
    void deferDeliveryForRetry(UUID shipmentId, UUID cityId, UUID tileId, double lat, double lon,
                               UUID orderId, String orderRef, LocalDate targetDate, String targetShift,
                               DeferReason reason);
}
