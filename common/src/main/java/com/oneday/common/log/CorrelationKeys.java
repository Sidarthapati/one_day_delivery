package com.oneday.common.log;

import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.events.BaseShipmentEvent;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.common.kafka.events.ExceptionsEvent;
import com.oneday.common.kafka.events.FlightEvent;
import com.oneday.common.kafka.events.HubEvent;
import com.oneday.common.kafka.events.ScanEvent;

/**
 * Pulls the correlation identifiers off any bus payload so the same extraction is used on both the
 * publish and consume seams. Handles the shipment-bearing event types explicitly (to get
 * {@code shipmentRef}/{@code parcelId} where they exist) and falls back to {@link DomainEvent#partitionKey()}
 * for everything else.
 *
 * <p>Prefer this central extractor over a per-event marker interface: all bus-facing payloads live
 * in {@code common}, so one switch keeps the mapping in a single place and needs no edits to the
 * event classes.</p>
 */
public record CorrelationKeys(String shipmentId, String shipmentRef, String parcelId) {

    private static final CorrelationKeys EMPTY = new CorrelationKeys(null, null, null);

    public static CorrelationKeys from(Object payload) {
        if (payload == null) {
            return EMPTY;
        }
        if (payload instanceof BaseShipmentEvent e) {
            return new CorrelationKeys(str(e.getShipmentId()), e.getShipmentRef(), null);
        }
        if (payload instanceof DaLifecycleEvent e) {
            return new CorrelationKeys(str(e.shipmentId()), e.shipmentRef(), str(e.parcelId()));
        }
        if (payload instanceof ScanEvent e) {
            return new CorrelationKeys(str(e.shipmentId()), null, e.parcelId());
        }
        if (payload instanceof HubEvent e) {
            return new CorrelationKeys(str(e.shipmentId()), null, null);
        }
        if (payload instanceof FlightEvent e) {
            return new CorrelationKeys(str(e.shipmentId()), null, null);
        }
        if (payload instanceof ExceptionsEvent e) {
            return new CorrelationKeys(str(e.shipmentId()), null, null);
        }
        if (payload instanceof DomainEvent e) {
            // Hub/cron/sla payload families etc. — partitionKey is the shipmentId for shipment-scoped
            // events; for the few actor/route-scoped ones it is the best available correlation key.
            return new CorrelationKeys(e.partitionKey(), null, null);
        }
        return EMPTY;
    }

    /** True when at least one identifier was found. */
    public boolean isEmpty() {
        return shipmentId == null && shipmentRef == null && parcelId == null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
