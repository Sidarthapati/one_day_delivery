package com.oneday.sla.dto;

import com.oneday.common.domain.enums.EscalationLevel;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.SlaEscalation;

import java.time.Instant;
import java.util.UUID;

/** An escalation as shown in the red queue / shipment detail, with its acknowledgement status. */
public record SlaEscalationView(
        UUID id,
        UUID shipmentId,
        String shipmentRef,
        SlaLegType leg,
        SlaState toState,
        EscalationLevel level,
        String city,
        String reasonCode,
        Instant projectedFinishAt,
        Instant createdAt,
        boolean acknowledged,
        boolean resolved) {

    public static SlaEscalationView from(SlaEscalation e, boolean acknowledged, boolean resolved) {
        return new SlaEscalationView(e.getId(), e.getShipmentId(), e.getShipmentRef(), e.getLeg(),
                e.getToState(), e.getLevel(), e.getCity(), e.getReasonCode(), e.getProjectedFinishAt(),
                e.getCreatedAt(), acknowledged, resolved);
    }
}
