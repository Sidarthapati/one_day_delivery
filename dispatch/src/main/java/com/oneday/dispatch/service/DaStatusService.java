package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.dispatch.service.model.DaQueue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The hot-path authority for live DA state. Status, GPS and per-DA task queues live in memory
 * (three maps) and are flushed to {@code da_status} on a timer — reads never hit the DB. This is
 * the single seam other M5 components go through to observe or mutate a DA's runtime state.
 */
public interface DaStatusService {

    /**
     * Seed all three in-memory maps for one DA at shift load. Idempotent: calling it again for a DA
     * already loaded leaves the live GPS/status untouched (safe on pod restart).
     *
     * @param cronAssignment the DA's cron meeting for the day, or {@code null} if none scheduled.
     */
    void initShift(UUID daId, UUID cityId, LocalDate shiftDate, String shiftType,
                   DaCronAssignment cronAssignment);

    /**
     * Record a GPS ping: update the live position + heartbeat and mark the DA dirty. GPS is
     * display/tracking only (Jul-20: the proximity geofence was removed). A ping while
     * {@code OFFLINE}/{@code ABSENT} resumes the DA to {@code IDLE}.
     */
    void updateGps(UUID daId, double lat, double lon, Instant timestamp);

    /**
     * Manual "Mark arrived" — the DA taps it at the van meeting vertex, replacing the removed
     * geofence. {@code CRON_LOCKED → AT_CRON}; already {@code AT_CRON} is a no-op; any other
     * status → 409.
     */
    void markArrivedAtCron(UUID daId);

    /** Set a DA's status in memory and synchronously through to {@code da_status} (under the DA lock). */
    void updateStatus(UUID daId, DaStatusEnum newStatus);

    /** Read a DA's current status from memory only; {@code null} if the DA isn't loaded. */
    DaStatusEnum getStatus(UUID daId);

    /** Live in-memory state for a DA, or {@code null} if not loaded. */
    DaLiveStatus getLiveStatus(UUID daId);

    /** The in-memory task queue for a DA, or {@code null} if not loaded. */
    DaQueue getQueue(UUID daId);

    /**
     * Register (replacing any prior set) the tiles a DA serves today, maintaining the tile→DA
     * reverse index the assignment engine uses to find candidate DAs for an incoming task's tile.
     */
    void setTerritory(UUID daId, List<UUID> tileIds);

    /** Loaded DAs whose territory includes {@code tileId} (order unspecified); empty if none. */
    List<UUID> dasForTile(UUID tileId);

    /**
     * Run {@code work} while holding the DA's exclusive lock, so cron-feasibility + queue insertion
     * are atomic per DA. The lock is reentrant — code inside may call {@link #updateStatus}.
     */
    <T> T withDaLock(UUID daId, Supplier<T> work);

    /** Snapshot of every DA currently loaded in memory (the day's roster). */
    java.util.Set<UUID> loadedDaIds();

    /** Batch-flush every dirty DA's live state to {@code da_status}; clean rows are skipped. */
    void flushDirtyStatuses();

    /** Drop all in-memory state (called at shift end, after a final flush). */
    void clearAll();
}
