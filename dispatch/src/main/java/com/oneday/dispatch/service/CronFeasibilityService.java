package com.oneday.dispatch.service;

import com.oneday.dispatch.service.model.LatLon;

import java.time.Instant;
import java.util.List;

/**
 * The algorithmic heart of M5: given a DA's current position, queued stops, and cron meeting, decide
 * whether an incoming task can be inserted without the DA missing the van rendezvous — the
 * cron-meeting hard constraint (design §8) — and at which queue position it costs the least extra
 * travel (cheapest-insertion heuristic, design §9).
 *
 * <p>Pure and side-effect free, so it is exhaustively unit-tested in isolation before any messaging
 * or persistence wires into it.</p>
 */
public interface CronFeasibilityService {

    FeasibilityResult checkFeasibility(FeasibilityRequest request);

    /**
     * Slack (seconds) the DA has left at the cron vertex after visiting {@code orderedStops} in the
     * given order, starting from {@code current}/{@code currentTime}. Positive = arrives with margin;
     * negative = would miss the meeting. Used by the reorder to build the largest priority-ordered
     * prefix that still makes the cron (haversine estimate — no OSRM per reorder). {@code cityId}
     * selects the per-city travel estimate (null → global).
     */
    long cronSlackSeconds(LatLon current, Instant currentTime, List<FeasibilityStop> orderedStops,
                          LatLon cronVertex, Instant meetingTime, String cityId);
}
