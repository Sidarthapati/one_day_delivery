package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.DaEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * The rich payload M5 (dispatch) PRODUCES on {@code oneday.da.events}
 * ({@link com.oneday.common.kafka.EventStreams#DA_EVENTS}), routing key = {@link DaEventType} name.
 *
 * <p>M4 consumes the same messages through the minimal {@link DaEvent} reader — it needs only
 * {@code shipmentId} + {@code eventType} to drive its state machine and ignores everything else
 * ({@code @JsonIgnoreProperties}). So this record may grow new <em>nullable</em> fields per event
 * type without breaking any consumer; the exact per-{@link DaEventType} field set is finalised in
 * Q-M4-6. Fields not relevant to a given event type are simply left null.</p>
 *
 * <p><b>⚠️ M6 also consumes {@code DA_EVENTS} now.</b> {@code routing.events.DaFeedConsumer} binds
 * its queue to this exchange with a catch-all ({@code #}) and reads messages as a <em>parcel-level</em>
 * {@code DaParcelPickedUpEvent(parcelId, cityId, daId, validDate, pickedUpAt)} to bind a collected
 * first-mile parcel to a van loop. This shipment-level record does <b>not</b> satisfy that shape
 * (no {@code parcelId}/{@code validDate}/{@code pickedUpAt}). <b>Do not wire an M5 producer to
 * {@code DA_EVENTS} until the M5↔M6 contract is settled</b> — see
 * {@code docs/M5/M5-Implementation-Plan.md} addendum §7 (parcel-vs-shipment id, routing-key
 * narrowing, and the {@code PICKUP_COMPLETED} bind trigger).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DaLifecycleEvent(
        UUID eventId,
        DaEventType eventType,
        String schemaVersion,
        Instant occurredAt,
        UUID shipmentId,     // null for DA-scoped events (e.g. DA_ABSENT, QUEUE_REORDERED)
        String shipmentRef,
        UUID daId,
        UUID cityId,
        Double lat,          // DA / task location at the time of the event, where relevant
        Double lon,
        String reasonCode    // failure / miss reason (PICKUP_FAILED, DROP_FAILED, CRON_MISSED); else null
) implements DomainEvent {

    /** Schema version stamped on M5 events; bump on a breaking payload change. */
    public static final String SCHEMA_VERSION = "1.0";

    @Override
    public String partitionKey() {
        // M4 correlates by shipment; DA-scoped events (no shipment) order by DA.
        if (shipmentId != null) {
            return shipmentId.toString();
        }
        return daId != null ? daId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return eventType != null ? eventType.name() : null;
    }
}
