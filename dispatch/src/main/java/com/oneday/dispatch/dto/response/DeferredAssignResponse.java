package com.oneday.dispatch.dto.response;

import com.oneday.dispatch.domain.DeferReason;
import com.oneday.dispatch.service.AssignmentOutcome;
import com.oneday.dispatch.service.AssignmentResult;

import java.util.UUID;

/** API view of an {@link AssignmentResult} for the station manager's manual-assign action. */
public record DeferredAssignResponse(
        AssignmentOutcome outcome,
        UUID daId,
        Integer queuePosition,
        UUID deferredId,
        DeferReason deferReason) {

    public static DeferredAssignResponse from(AssignmentResult r) {
        return new DeferredAssignResponse(r.outcome(), r.daId(), r.queuePosition(), r.deferredId(), r.deferReason());
    }
}
