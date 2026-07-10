package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.util.UUID;

/**
 * The system-generated, append-only manifest for a sealed bag is ready for M9 handover (§7.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManifestGeneratedEvent(
        UUID bagId,
        UUID manifestId,
        String flightNo) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.MANIFEST_GENERATED;
    }

    @Override
    public String partitionKey() {
        return bagId != null ? bagId.toString() : null;
    }
}
