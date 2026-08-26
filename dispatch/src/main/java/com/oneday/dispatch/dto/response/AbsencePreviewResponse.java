package com.oneday.dispatch.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The previewed midday-absence reassignment a station manager reviews before applying. Three task
 * buckets, exactly as the console renders them: loose (not-yet-collected) tasks that follow their hex
 * to a new owner, in-custody parcels that become CUSTODY_COLLECT handoffs, and orphaned work with no
 * live neighbor. {@code autoApproveAt} is when the plan self-applies if no one acts.
 */
public record AbsencePreviewResponse(
        UUID eventId,
        UUID cityId,
        List<UUID> absentDaIds,
        Instant autoApproveAt,
        int reassignedHexCount,
        int orphanHexCount,
        List<ReceiverLoad> receivers,
        List<TaskMove> looseTasks,
        List<CustodyMove> custodyTasks,
        List<TaskMove> orphanTasks) {

    /** A neighbor and how many of the absent DAs' hexes it inherits. */
    public record ReceiverLoad(UUID daId, int gainedHexes) {}

    /** A not-yet-collected task moving from the absent DA to the hex's new owner. */
    public record TaskMove(UUID shipmentId, String orderRef, String taskType, UUID fromDaId, UUID toDaId) {}

    /** An in-custody parcel the new owner must physically collect from the absent DA. */
    public record CustodyMove(UUID shipmentId, String orderRef, UUID fromDaId, UUID toDaId,
                              double collectLat, double collectLon) {}
}
