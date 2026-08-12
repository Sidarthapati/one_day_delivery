package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.HubEventType;

import java.util.UUID;

/**
 * Inbound event consumed by M4 from {@code oneday.hub.events} (produced by M7).
 *
 * <p>Minimal consumption contract — see {@link DaEvent} for the tolerant-reader rationale.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HubEvent(UUID shipmentId,
                       // M7's concrete events expose eventType() as an interface method, which Jackson
                       // serializes camelCase ("eventType") even though record components are snake_case;
                       // accept that key so the umbrella binds when a concrete event is inferred to HubEvent.
                       @JsonAlias("eventType") HubEventType eventType) implements DomainEvent {

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return eventType != null ? eventType.name() : null;
    }
}
