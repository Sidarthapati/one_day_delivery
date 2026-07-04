package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.HubEventType;

/**
 * Base contract for the typed payloads M7 (hub) publishes on {@code oneday.hub.events}
 * (M7 design §14.1). Mirrors {@code cron.CronEventPayload}: a plain record per shape, the
 * {@link HubEventType} as the discriminator, and {@link DomainEvent#partitionKey()} the entity the
 * event is about (parcel/shipment id, or bag id) so per-entity ordering holds.
 *
 * <p>Consumers that only need the discriminator can use the tolerant
 * {@link com.oneday.common.kafka.events.HubEvent} reader instead.</p>
 *
 * <p>Sealed so the set of hub event shapes is closed; PR #2/#3 add their payloads to the permits.</p>
 */
public sealed interface HubEventPayload extends DomainEvent permits
        StandAssignedEvent,
        BagCreatedEvent,
        BagSealedEvent,
        ManifestGeneratedEvent,
        HubDiscrepancyEvent,
        ParcelSortedForDeliveryEvent,
        DeliveryBagCreatedEvent,
        DestSortCompleteEvent,
        SameCityOutboundEvent,
        BagRescheduledEvent,
        HubOverloadAlertEvent {

    // Serialized into the JSON body (not just the routing key) so the tolerant
    // com.oneday.common.kafka.events.HubEvent reader can recover the discriminator. The concrete
    // records ignore it on read (@JsonIgnoreProperties(ignoreUnknown=true)); it is derived, not stored.
    @JsonProperty("eventType")
    HubEventType eventType();

    @Override
    default String eventTypeName() {
        return eventType().name();
    }
}
