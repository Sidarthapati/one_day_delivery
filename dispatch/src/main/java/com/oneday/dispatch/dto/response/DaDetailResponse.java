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
     * @param shipmentRef    human ref (e.g. {@code 1DD-BLR-…}) for the parcel-detail link; null if unresolved
     * @param urgency        RED · AMBER · GREEN · DONE — from the real M10 SLA colour when the shipment has an
     *                       SLA row, else a dispatch-local expectedEta fallback (see {@code DispatchMetricsServiceImpl})
     * @param actByAt        soonest SLA hard window (from M10); null when no SLA row / clock not started
     * @param urgencyMinutes minutes past target (from M10); negative = slack; null when no SLA row
     */
    /**
     * The lifecycle timestamps ({@code assignedAt}/{@code startedAt}/{@code arrivedAt}/{@code completedAt})
     * let the client reconstruct what the DA was doing at any moment on the GPS-trail scrubber — which
     * parcels were assigned-but-open, in progress, on-site, or already done at a given fix time.
     */
    public record DaTaskItem(
            UUID taskId,
            UUID shipmentId,
            String shipmentRef,
            String orderRef,          // parent order back-ref (null for legacy/pre-order tasks)
            String taskType,
            String status,
            Instant expectedEta,
            String urgency,
            Instant actByAt,
            Integer urgencyMinutes,
            Instant assignedAt,
            Instant startedAt,
            Instant arrivedAt,
            Instant completedAt) {
    }

    public record DayStops(LocalDate date, long done, long failed) {
    }
}
