package com.oneday.barcode.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes {@code ScanEvent} to {@code oneday.scan.events} once the ledger write has committed
 * (mirrors {@code orders.ShipmentEventProducer}). Only scans whose type is a {@link ScanEventType}
 * are broadcast — van custody scans (VAN_LOAD/…) are ledger-only (D-004), so they fall through.
 */
@Component
public class ScanEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ScanEventProducer.class);

    private final EventPublisher eventPublisher;

    ScanEventProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScanRecorded(ScanRecorded e) {
        ScanEventType type = broadcastType(e.scanType());
        if (type == null) {
            return; // not a broadcastable type (e.g. a van custody scan) — ledger-only
        }
        log.debug("Publishing ScanEvent {} shipmentId={} parcelId={}", type, e.shipmentId(), e.parcelId());
        eventPublisher.publish(EventStreams.SCAN_EVENTS,
                new ScanEvent(e.shipmentId(), type, e.parcelId(), e.scannedAt()));
    }

    private static ScanEventType broadcastType(String scanType) {
        try {
            return ScanEventType.valueOf(scanType);
        } catch (IllegalArgumentException notBroadcastable) {
            return null; // VanScanType values live outside ScanEventType
        }
    }
}
