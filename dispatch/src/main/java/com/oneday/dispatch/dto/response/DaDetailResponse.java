package com.oneday.dispatch.dto.response;

import com.oneday.dispatch.dto.response.DispatchExecutionStats.DaPace;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A single DA's control-tower detail for a date: who they are, how they're pacing, today's tasks ordered
 * by urgency (RED → IN_PROGRESS → QUEUED → DONE), and a short per-day history for the trend.
 *
 * @param pace      today's pace (done / last-hour / pending / avg-per-hour), name+phone included
 * @param completed today's completed tasks · @param failed today's failed attempts
 * @param tasks     today's tasks, most-urgent first
 * @param history   per-day stops (done/failed), oldest first — the mini-trend
 */
public record DaDetailResponse(
        UUID daId,
        String name,
        String phone,
        UUID cityId,
        LocalDate date,
        DaPace pace,
        long completed,
        long failed,
        List<DaTaskItem> tasks,
        List<DayStops> history) {

    /**
     * @param urgency RED (failed / past-ETA active) · AMBER (in-progress on-track) · GREEN (queued
     *                on-track) · DONE (completed) — derived from status + expectedEta vs now.
     */
    public record DaTaskItem(
            UUID taskId,
            UUID shipmentId,
            String taskType,
            String status,
            Instant expectedEta,
            String urgency) {
    }

    public record DayStops(LocalDate date, long done, long failed) {
    }
}
