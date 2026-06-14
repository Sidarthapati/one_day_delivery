package com.oneday.routing.service;

import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.TravelMatrix;

/**
 * Stage 3 of the nightly pipeline (§7.3, M6-D-002/-006): the capacitated VRP with simultaneous
 * pickup &amp; delivery and a route-span (cycle) bound, solved over {@code {hub} ∪ selected
 * vertices} for one loop. Two engines implement this — OR-Tools and a Clarke–Wright savings
 * fallback — behind {@code VanRouteSolverSelector} (mirrors M3's CP-SAT → BFS fallback).
 */
public interface VanRouteSolver {

    /**
     * Solve one loop. Vehicles start/end at the hub (node 0); peak load ≤ {@code capacityPackets};
     * route span ≤ {@code cycleMaxMinutes}; every meeting vertex visited exactly once. Returns
     * {@link SolveResult#feasible()} = false when no routing within these bounds exists for
     * {@code vansAvailable} vans.
     */
    SolveResult solve(TravelMatrix matrix, int vansAvailable, int capacityPackets, int cycleMaxMinutes);

    /**
     * Fast feasibility-only variant of {@link #solve} for fleet sizing: may stop at the first feasible
     * solution rather than optimise, so {@code recommendVanCount} can binary-search the minimum fleet
     * cheaply. Default delegates to {@link #solve}; OR-Tools overrides it with a first-solution config.
     */
    default SolveResult probe(TravelMatrix matrix, int vansAvailable, int capacityPackets, int cycleMaxMinutes) {
        return solve(matrix, vansAvailable, capacityPackets, cycleMaxMinutes);
    }
}
