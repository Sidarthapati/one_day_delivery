package com.oneday.hub.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import com.oneday.common.log.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The dock scan is the record of hub custody (M8-SEAM until barcode/M8 owns it). Every physical hub
 * scan is emitted here on {@code oneday.scan.events} — the same exchange M8 uses — so M4's
 * {@code ScanEventsConsumer} advances the parcel. Direction (origin-in vs dest-in) is decided by the
 * caller from the parcel's prior M4 state, never by a console button. Every emit is best-effort: a
 * publish failure is logged and swallowed so it can never block the dock operation.
 */
@Component
public class HubArrivalScanProducer {

    private static final Logger log = LoggerFactory.getLogger(HubArrivalScanProducer.class);

    private final EventPublisher eventPublisher;

    HubArrivalScanProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Hub arrival scan (origin-in / dest-in) — the parcel physically hit this dock. */
    public void emitArrival(UUID shipmentId, String shipmentRef, UUID hubId, ScanEventType type, String direction) {
        publish(shipmentId, type);
        AuditLog.event("hub.arrival_scan")
                .kv("shipmentRef", shipmentRef)
                .kv("parcelId", shipmentId)
                .kv("hubId", hubId)
                .kv("direction", direction)
                .kv("scanType", type.name())
                .log();
    }

    /** Origin-out scan fired per parcel when a sealed flight bag is dispatched to the airport. */
    public void emitOriginOut(UUID shipmentId, String shipmentRef, UUID bagId, String flightNo) {
        publish(shipmentId, ScanEventType.HUB_ORIGIN_OUT);
        AuditLog.event("hub.origin_out_scan")
                .kv("shipmentRef", shipmentRef)
                .kv("parcelId", shipmentId)
                .kv("bagId", bagId)
                .kv("flightNo", flightNo)
                .log();
    }

    private void publish(UUID shipmentId, ScanEventType type) {
        try {
            eventPublisher.publish(EventStreams.SCAN_EVENTS, new ScanEvent(shipmentId, type));
        } catch (Exception e) {   // never block a dock scan on a publish failure
            log.warn("Hub scan {} for shipment {} failed to publish (non-blocking): {}",
                    type, shipmentId, e.getMessage());
        }
    }
}
