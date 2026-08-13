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
public record HubEvent(
                       // Most M7 events key their parcel by "shipmentId", but the per-parcel
                       // DestSortCompleteEvent names the same value "parcelId". Accept both so
                       // DEST_SORT_COMPLETE binds a non-null id here — otherwise the consumer's
                       // shipmentId==null guard silently drops it and the parcel never advances
                       // AT_DEST_HUB → DEST_HUB_PROCESSING (and the whole last mile stalls).
                       @JsonAlias({"parcelId", "parcel_id"}) UUID shipmentId,
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
