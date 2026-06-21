package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.TaskType;

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
     * M3); {@code paymentMode} is stored for COD-aware handling and may be null.
     */
    AssignmentResult assignPickup(UUID shipmentId, UUID cityId, double lat, double lon,
                                  UUID originTileId, String paymentMode);

    /** Assign a last-mile delivery. {@code destTileId} may be null (resolved from {@code lat/lon}). */
    AssignmentResult assignDelivery(UUID shipmentId, UUID cityId, double lat, double lon, UUID destTileId);

    /**
     * Cancel a shipment's active task. A QUEUED task is removed and the queue resequenced; an
     * IN_PROGRESS task cannot be cancelled here (throws {@link TaskInProgressException} — M11 resolves
     * it operationally). No active task → no-op.
     */
    void cancelTask(UUID shipmentId, TaskType taskType);

    /** Re-attempt a previously deferred dispatch; flips it to ASSIGNED on success, leaves it PENDING otherwise. */
    AssignmentResult reassignDeferred(UUID deferredId);
}
