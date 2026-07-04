package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.DaEventType;

import java.time.Instant;
import java.time.LocalDate;
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
 * <p><b>M4 and M6 both consume {@code DA_EVENTS} — this is the single rich type on that exchange.</b>
 * Both bind a catch-all ({@code #}) queue, take this type as the listener param, and dispatch in code
 * by {@link #eventType} (the project's standing consumer pattern). M4 ({@code DaEventsConsumer}) reads
 * {@code shipmentId} + {@code eventType} to drive its state machine; M6 ({@code DaFeedConsumer}) acts
 * only on {@code PICKUP_COMPLETED}, reading {@code parcelId} / {@code cityId} / {@code daId} /
 * {@code validDate} / {@code occurredAt} (= pickup time) to bind the collected first-mile parcel to a
 * van loop. The converter is header-based ({@code DefaultClassMapper}), so producer and both consumers
 * must agree on this one type — they do.</p>
 *
 * <p><b>Parcel-vs-shipment id (v1):</b> M8 barcodes are not minted yet, so {@code parcelId == shipmentId}
 * for now. The field is carried explicitly so the real barcode can flow without a schema change once M8
 * lands. {@code parcelId} / {@code validDate} are populated for parcel-scoped events (e.g.
 * {@code PICKUP_COMPLETED}); both are null for DA-scoped events (DA_ABSENT, QUEUE_REORDERED).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DaLifecycleEvent(
        UUID eventId,
        DaEventType eventType,
        String schemaVersion,
        Instant occurredAt,  // for PICKUP_COMPLETED this is the pickup time M6 binds on
        UUID shipmentId,     // null for DA-scoped events (e.g. DA_ABSENT, QUEUE_REORDERED)
        String shipmentRef,
        UUID daId,
        UUID cityId,
        Double lat,          // DA / task location at the time of the event, where relevant
        Double lon,
        String reasonCode,   // failure / miss reason (PICKUP_FAILED, DROP_FAILED, CRON_MISSED); else null
        UUID parcelId,       // M6 collect-bind key; == shipmentId in v1 (no M8 barcode yet); null if N/A
        LocalDate validDate  // operating date for parcel-scoped events; null for DA-scoped events
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
