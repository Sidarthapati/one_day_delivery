package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.dispatch.service.model.DaQueue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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
     * Record a GPS ping: update the live position + heartbeat and mark the DA dirty. If the DA is
     * {@code CRON_LOCKED} and now within the configured proximity of its cron vertex, flips to
     * {@code AT_CRON}. A ping while {@code OFFLINE}/{@code ABSENT} resumes the DA to {@code IDLE}.
     */
    void updateGps(UUID daId, double lat, double lon, Instant timestamp);

    /** Set a DA's status in memory and synchronously through to {@code da_status} (under the DA lock). */
    void updateStatus(UUID daId, DaStatusEnum newStatus);

    /** Read a DA's current status from memory only; {@code null} if the DA isn't loaded. */
    DaStatusEnum getStatus(UUID daId);

    /** Live in-memory state for a DA, or {@code null} if not loaded. */
    DaLiveStatus getLiveStatus(UUID daId);

    /** The in-memory task queue for a DA, or {@code null} if not loaded. */
    DaQueue getQueue(UUID daId);

    /** Snapshot of every DA currently loaded in memory (the day's roster). */
    java.util.Set<UUID> loadedDaIds();

    /** Batch-flush every dirty DA's live state to {@code da_status}; clean rows are skipped. */
    void flushDirtyStatuses();

    /** Drop all in-memory state (called at shift end, after a final flush). */
    void clearAll();
}
