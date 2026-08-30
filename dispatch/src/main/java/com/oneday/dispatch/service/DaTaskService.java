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
     * Record the DA arriving at the stop (pickup or delivery). Stamps {@code arrived_at} once
     * (idempotent — a resumed screen re-tap is a no-op) and writes an audit line; does not change
     * task status. Best-effort from the app's side.
     */
    DaTaskView markArrivedAtStop(UUID daId, UUID taskId);

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

    /**
     * DA-initiated re-attempt: a FAILED task (e.g. "customer not home") → QUEUED, re-queued at the end
     * of the DA's own list so it's retried after current work this shift. Clears the terminal
     * timestamps and emits QUEUE_REORDERED. Only a FAILED task the DA owns is eligible (409 otherwise).
     */
    DaTaskView reattempt(UUID daId, UUID taskId);

    /** VAN_MEETING city: DELIVERY task QUEUED → IN_PROGRESS (collected from the van); emits DROP_COLLECTED. */
    DaTaskView markDropCollected(UUID daId, UUID taskId);

    /**
     * HUB_RETURN city (no van): DELIVERY task QUEUED → IN_PROGRESS when the DA collects the parcel FROM
     * the hub for last-mile. Emits DROP_COLLECTED plus the hub-dest custody scan.
     */
    DaTaskView recordHubCollect(UUID daId, UUID taskId);

    /** DELIVERY task IN_PROGRESS → COMPLETED; emits DROP_COMPLETED (+ COD_COLLECTED if cash taken). */
    DaTaskView markDropCompleted(UUID daId, UUID taskId, boolean codCollected);

    /**
     * Midday-absence handoff: a CUSTODY_COLLECT task (QUEUED/IN_PROGRESS) → COMPLETED when the covering
     * DA physically takes the in-custody parcel from the absent DA. Records the append-only M8 DA→DA
     * custody scan (the source of truth that custody moved), emits CUSTODY_COLLECTED, and spawns the
     * parcel's onward leg (the original PICKUP/DELIVERY, in-hand) on this DA so it isn't stranded.
     * {@code parcelScans} must be non-empty.
     */
    DaTaskView recordCustodyCollect(UUID daId, UUID taskId, List<String> parcelScans);

    /**
     * Delivery-failure carry-back: a RETURN_TO_HUB task (QUEUED/IN_PROGRESS) → COMPLETED when the DA
     * scans the in-hand undelivered parcel back in at the hub. Emits the M8 hub-return dock-receive scan
     * (the re-entry point for the return / next-day redelivery pipeline).
     */
    DaTaskView recordReturnedToHub(UUID daId, UUID taskId);

    /**
     * Proactively pull a still-out delivery back for a receiver reschedule (not a door failure): cancel
     * the live DELIVERY task and, if the DA already had it in hand (IN_PROGRESS), spawn a RETURN_TO_HUB
     * carry-back so the parcel comes back to the hub instead of a wasted door attempt. Emits no
     * DROP_FAILED (the reject is counted as an attempt separately by M11). Returns {@code true} if a task
     * was recalled, {@code false} if there was no live delivery task (caller then just defers).
     */
    boolean recallDeliveryForReschedule(UUID shipmentId);
}
