package com.oneday.common.kafka.events.sla;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.domain.enums.EscalationLevel;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.SlaEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * M10 → ops / notification service on {@code oneday.sla.events}: a parcel's leg went RED and an
 * escalation was raised. Keyed by shipment so per-parcel ordering holds. The notification service
 * resolves the on-duty {@code level} owner for {@code city} and delivers the alert; the escalation
 * itself is already persisted append-only in {@code sla_escalation}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlaEscalationRaisedEvent(
        UUID escalationId,
        UUID shipmentId,
        String shipmentRef,
        SlaLegType leg,
        SlaState state,
        EscalationLevel level,
        String city,
        String reasonCode,
        Instant projectedFinishAt,
        Instant internalTargetAt,
        Instant raisedAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return SlaEventType.SLA_ESCALATION_RAISED.name();
    }
}
