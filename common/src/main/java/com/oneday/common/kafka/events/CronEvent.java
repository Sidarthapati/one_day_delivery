package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.CronEventType;

import java.util.UUID;

/**
 * Inbound event consumed by M4 from {@code oneday.cron.events} (produced by M6).
 *
 * <p>Minimal consumption contract — see {@link DaEvent} for the tolerant-reader rationale.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CronEvent(UUID shipmentId, CronEventType eventType) implements DomainEvent {

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return eventType != null ? eventType.name() : null;
    }
}
