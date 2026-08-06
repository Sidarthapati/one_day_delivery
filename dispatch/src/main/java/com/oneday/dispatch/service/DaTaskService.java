package com.oneday.dispatch.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DA task-lifecycle operations behind the DA app's endpoints. Each call loads the task, checks it
 * belongs to the DA (404 otherwise) and is in a legal state for the transition (409 otherwise),
 * updates {@code dispatch_queue} + the in-memory mirror, and emits the matching DA lifecycle event
 * (gated by {@code dispatch.events.publish-da-events}).
 */
public interface DaTaskService {

    /**
     * The DA's queue for a day, ordered by queue position — the app's task list.
     * {@code date} null → the DA's current operating day (shift zone).
     */
    List<DaTaskView> listTasks(UUID daId, LocalDate date);

    /** PICKUP task QUEUED → IN_PROGRESS (DA travelling to the sender). */
    DaTaskView markEnRoute(UUID daId, UUID taskId);

    /**
     * VAN_MEETING city: PICKUP task IN_PROGRESS → COMPLETED at the cron van. Records the cron handoff
     * and emits VAN_HANDOFF_COMPLETED. {@code parcelScans} must be non-empty (full M8 scan-ledger
     * validation lands with barcode integration).
     */
    DaTaskView recordVanHandoff(UUID daId, UUID taskId, List<String> parcelScans, UUID vanId);

    /**
     * HUB_RETURN city (no van): PICKUP task IN_PROGRESS → COMPLETED when the DA drops the collected
     * pickups AT the hub. Same lifecycle as {@link #recordVanHandoff} but emits HUB_RETURN_HANDOFF_COMPLETED
     * and the origin-hub scan. {@code parcelScans} must be non-empty.
     */
    DaTaskView recordHubHandoff(UUID daId, UUID taskId, List<String> parcelScans);

    /** Any active task → FAILED; emits PICKUP_FAILED or DROP_FAILED by task type. */
    DaTaskView markFailed(UUID daId, UUID taskId, String reason);

    /** VAN_MEETING city: DELIVERY task QUEUED → IN_PROGRESS (collected from the van); emits DROP_COLLECTED. */
    DaTaskView markDropCollected(UUID daId, UUID taskId);

    /**
     * HUB_RETURN city (no van): DELIVERY task QUEUED → IN_PROGRESS when the DA collects the parcel FROM
     * the hub for last-mile. Emits DROP_COLLECTED plus the hub-dest custody scan.
     */
    DaTaskView recordHubCollect(UUID daId, UUID taskId);

    /** DELIVERY task IN_PROGRESS → COMPLETED; emits DROP_COMPLETED (+ COD_COLLECTED if cash taken). */
    DaTaskView markDropCompleted(UUID daId, UUID taskId, boolean codCollected);
}
