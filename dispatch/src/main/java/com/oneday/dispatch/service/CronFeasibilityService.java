package com.oneday.dispatch.service;

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
}
