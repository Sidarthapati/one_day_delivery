package com.oneday.dispatch.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * M8-SEAM. In HUB_RETURN cities (the M6 gate off) the DA is the physical carrier to/from the hub, so
 * the hub-custody scans that M8 (barcode) will own are emitted here on {@code oneday.scan.events} as a
 * bridge until M8 lands — the M8 owner relocates these into M8 proper. Every emit is best-effort: a
 * publish failure is logged and swallowed so it can never block the DA's custody flow.
 *
 * <p>When a caller emits from inside a transaction, the actual publish is deferred to AFTER_COMMIT so a
 * later rollback never leaves the ledger describing a custody move that was never persisted (the seam
 * has no transactional {@code RabbitTemplate}). Outside a transaction it publishes inline.</p>
 */
@Component
public class HubScanSeamProducer {

    private static final Logger log = LoggerFactory.getLogger(HubScanSeamProducer.class);

    private final EventPublisher eventPublisher;

    public HubScanSeamProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** DA collected a dest parcel from the hub for last-mile (ledger only — M4 ignores it). */
    public void emitHubDestOut(UUID shipmentId) {
        emit(shipmentId, ScanEventType.HUB_DEST_OUT);
    }

    /** Midday absence: covering DA collected an in-custody parcel from the absent DA (ledger only). */
    public void emitDaCustodyTransfer(UUID shipmentId) {
        emit(shipmentId, ScanEventType.DA_CUSTODY_TRANSFER);
    }

    /** Delivery-failure carry-back: DA scanned an in-hand parcel back in at the hub (the dock-receive). */
    public void emitHubReturnIn(UUID shipmentId) {
        emit(shipmentId, ScanEventType.HUB_RETURN_IN);
    }

    private void emit(UUID shipmentId, ScanEventType type) {
        // Build the event NOW so occurredAt is the scan time, not the (possibly much later) commit time.
        ScanEvent event = new ScanEvent(shipmentId, type);
        // Inside a transaction → publish only once it commits (a rollback must not leave a phantom scan);
        // outside one → publish now.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
        } else {
            publish(event);
        }
    }

    private void publish(ScanEvent event) {
        try {
            eventPublisher.publish(EventStreams.SCAN_EVENTS, event);
        } catch (Exception e) {   // M8-SEAM: never block custody on a scan publish failure
            log.warn("M8-SEAM hub scan {} for shipment {} failed (non-blocking): {}",
                    event.eventType(), event.shipmentId(), e.getMessage());
        }
    }
}
