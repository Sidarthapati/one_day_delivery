package com.oneday.dispatch.service;

import java.util.List;
import java.util.UUID;

/**
 * DA task-lifecycle operations behind the DA app's endpoints. Each call loads the task, checks it
 * belongs to the DA (404 otherwise) and is in a legal state for the transition (409 otherwise),
 * updates {@code dispatch_queue} + the in-memory mirror, and emits the matching DA lifecycle event
 * (gated by {@code dispatch.events.publish-da-events}).
 */
public interface DaTaskService {

    /** PICKUP task QUEUED → IN_PROGRESS (DA travelling to the sender). */
    DaTaskView markEnRoute(UUID daId, UUID taskId);

    /**
     * PICKUP task IN_PROGRESS → COMPLETED at the cron van. Records the cron handoff and emits
     * VAN_HANDOFF_COMPLETED. {@code parcelScans} must be non-empty (full M8 scan-ledger validation
     * lands with barcode integration).
     */
    DaTaskView recordVanHandoff(UUID daId, UUID taskId, List<String> parcelScans, UUID vanId);

    /** Any active task → FAILED; emits PICKUP_FAILED or DROP_FAILED by task type. */
    DaTaskView markFailed(UUID daId, UUID taskId, String reason);

    /** DELIVERY task QUEUED → IN_PROGRESS (collected from the van); emits DROP_COLLECTED. */
    DaTaskView markDropCollected(UUID daId, UUID taskId);

    /** DELIVERY task IN_PROGRESS → COMPLETED; emits DROP_COMPLETED (+ COD_COLLECTED if cash taken). */
    DaTaskView markDropCompleted(UUID daId, UUID taskId, boolean codCollected);
}
