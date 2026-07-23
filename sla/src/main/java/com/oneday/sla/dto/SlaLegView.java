package com.oneday.sla.dto;

import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.SlaLeg;

import java.time.Instant;

/** One leg's SLA view for the control tower. */
public record SlaLegView(
        SlaLegType leg,
        int seq,
        int budgetMinutes,
        SlaState state,
        Instant startedAt,
        Instant deadlineAt,
        Instant completedAt,
        Instant projectedEndAt) {

    public static SlaLegView from(SlaLeg l) {
        return new SlaLegView(l.getLeg(), l.getSeq(), l.getBudgetMinutes(), l.getState(),
                l.getStartedAt(), l.getDeadlineAt(), l.getCompletedAt(), l.getProjectedEndAt());
    }
}
