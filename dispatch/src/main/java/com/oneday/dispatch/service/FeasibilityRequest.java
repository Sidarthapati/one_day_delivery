package com.oneday.dispatch.service;

import com.oneday.dispatch.service.model.LatLon;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A self-contained cron-feasibility question: can {@code newTask} be inserted into a DA's
 * {@code existingQueue} and still let the DA reach the cron vertex by {@code scheduledMeetingTime}?
 *
 * <p>The engine is a pure function of this request — it reads no live state. PR #7 builds the
 * request from {@code DaLiveStatus} (current position / time) and the cached tile→service map:</p>
 * <ul>
 *   <li>{@code currentPosition} / {@code currentTime} — the DA's last-known GPS and now; or, when a
 *       task is IN_PROGRESS, that task's location and its {@code expected_eta} (design §8.4).</li>
 *   <li>{@code existingQueue} — the QUEUED stops in order (excludes the IN_PROGRESS stop, which is
 *       folded into {@code currentPosition}/{@code currentTime}).</li>
 *   <li>{@code cronVertex} / {@code scheduledMeetingTime} — the van rendezvous and its latest
 *       arrival time, from {@code da_cron_assignment}.</li>
 * </ul>
 */
public record FeasibilityRequest(
        LatLon currentPosition,
        Instant currentTime,
        List<FeasibilityStop> existingQueue,
        FeasibilityStop newTask,
        LatLon cronVertex,
        Instant scheduledMeetingTime) {

    public FeasibilityRequest {
        Objects.requireNonNull(currentPosition, "currentPosition");
        Objects.requireNonNull(currentTime, "currentTime");
        Objects.requireNonNull(newTask, "newTask");
        Objects.requireNonNull(cronVertex, "cronVertex");
        Objects.requireNonNull(scheduledMeetingTime, "scheduledMeetingTime");
        existingQueue = existingQueue == null ? List.of() : List.copyOf(existingQueue);
    }
}
