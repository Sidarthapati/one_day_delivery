package com.oneday.dispatch.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * M8-SEAM. In HUB_RETURN cities (the M6 gate off) the DA is the physical carrier to/from the hub, so
 * the hub-custody scans that M8 (barcode) will own are emitted here on {@code oneday.scan.events} as a
 * bridge until M8 lands — the M8 owner relocates these into M8 proper. Every emit is best-effort: a
 * publish failure is logged and swallowed so it can never block the DA's custody flow.
 */
@Component
public class HubScanSeamProducer {

    private static final Logger log = LoggerFactory.getLogger(HubScanSeamProducer.class);

    private final EventPublisher eventPublisher;

    public HubScanSeamProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** DA dropped a collected pickup at the origin hub → M4 (HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB). */
    public void emitHubOriginIn(UUID shipmentId) {
        emit(shipmentId, ScanEventType.HUB_ORIGIN_IN);
    }

    /** DA collected a dest parcel from the hub for last-mile (ledger only — M4 ignores it). */
    public void emitHubDestOut(UUID shipmentId) {
        emit(shipmentId, ScanEventType.HUB_DEST_OUT);
    }

    private void emit(UUID shipmentId, ScanEventType type) {
        try {
            eventPublisher.publish(EventStreams.SCAN_EVENTS, new ScanEvent(shipmentId, type));
        } catch (Exception e) {   // M8-SEAM: never block custody on a scan publish failure
            log.warn("M8-SEAM hub scan {} for shipment {} failed (non-blocking): {}",
                    type, shipmentId, e.getMessage());
        }
    }
}
