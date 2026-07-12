package com.oneday.barcode.events;

import java.time.Instant;
import java.util.UUID;

/**
 * In-process Spring event raised inside the ledger-write transaction. {@link ScanEventProducer}
 * consumes it AFTER_COMMIT and decides whether to broadcast an outbound {@code ScanEvent} — the
 * ledger is the durable truth, the event is a downstream signal.
 */
public record ScanRecorded(UUID shipmentId, String parcelId, String scanType, Instant scannedAt) {
}
