package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.DeferReason;

import java.util.UUID;

/**
 * Outcome of an {@link DispatchService} assignment call. On success {@code daId} + {@code queuePosition}
 * are set; on a defer {@code deferredId} (when a row was persisted) + {@code deferReason} are set.
 */
public record AssignmentResult(
        AssignmentOutcome outcome,
        UUID daId,
        Integer queuePosition,
        UUID deferredId,
        DeferReason deferReason,
        boolean crossTerritory) {

    public static AssignmentResult assigned(UUID daId, int queuePosition) {
        return new AssignmentResult(AssignmentOutcome.ASSIGNED, daId, queuePosition, null, null, false);
    }

    public static AssignmentResult crossTerritory(UUID daId, int queuePosition) {
        return new AssignmentResult(AssignmentOutcome.CROSS_TERRITORY_ASSIGNED, daId, queuePosition, null, null, true);
    }

    public static AssignmentResult deferred(UUID deferredId, DeferReason reason) {
        return new AssignmentResult(AssignmentOutcome.DEFERRED, null, null, deferredId, reason, false);
    }
}
