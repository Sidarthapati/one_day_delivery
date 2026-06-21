package com.oneday.dispatch.service;

/**
 * Outcome of a cron-feasibility check (design §8–§9).
 *
 * @param feasible           whether the DA can take {@code newTask} and still make the cron meeting
 * @param bestInsertionIndex cheapest-insertion position in the existing queue (0 = before the first
 *                           stop, n = after the last); always set, even when infeasible, so callers
 *                           can log the would-be slot
 * @param cronSlackSeconds   seconds of margin left at the cron vertex after the best insertion
 *                           (positive = arrives early; negative = would miss the meeting)
 * @param extraTravelSeconds added travel the insertion costs at {@code bestInsertionIndex}
 * @param usedOsrm           true if OSRM confirmed this borderline decision; false on the haversine
 *                           fast path or the conservative breaker-open fallback
 */
public record FeasibilityResult(
        boolean feasible,
        int bestInsertionIndex,
        long cronSlackSeconds,
        long extraTravelSeconds,
        boolean usedOsrm) {
}
