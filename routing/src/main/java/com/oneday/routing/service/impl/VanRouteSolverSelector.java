package com.oneday.routing.service.impl;

import com.oneday.routing.service.VanRouteSolver;
import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.TravelMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * The {@link VanRouteSolver} the pipeline injects (M6-D-006). Runs OR-Tools; if its native library
 * is missing or it throws, falls back to Clarke–Wright savings — the same graceful-degradation
 * pattern as M3's CP-SAT → BFS. {@code @Primary} so a plain {@code VanRouteSolver} injection picks
 * this over the two underlying engines.
 */
@Service
@Primary
class VanRouteSolverSelector implements VanRouteSolver {

    private static final Logger log = LoggerFactory.getLogger(VanRouteSolverSelector.class);

    private final OrToolsVanRouteSolver orTools;
    private final SavingsVanRouteSolver savings;

    VanRouteSolverSelector(OrToolsVanRouteSolver orTools, SavingsVanRouteSolver savings) {
        this.orTools = orTools;
        this.savings = savings;
    }

    @Override
    public SolveResult solve(TravelMatrix matrix, int vansAvailable, int capacityPackets, int cycleMaxMinutes) {
        try {
            return orTools.solve(matrix, vansAvailable, capacityPackets, cycleMaxMinutes);
        } catch (Throwable t) {
            // UnsatisfiedLinkError (no native lib) or any solver error → savings fallback.
            log.warn("OR-Tools solve failed ({}); falling back to Clarke–Wright savings", t.toString());
            return savings.solve(matrix, vansAvailable, capacityPackets, cycleMaxMinutes);
        }
    }

    @Override
    public SolveResult solve(TravelMatrix matrix, int vansAvailable, int capacityPackets,
                             int cycleMaxMinutes, boolean allowDrops) {
        try {
            return orTools.solve(matrix, vansAvailable, capacityPackets, cycleMaxMinutes, allowDrops);
        } catch (Throwable t) {
            log.warn("OR-Tools drop-and-flag solve failed ({}); falling back to Clarke–Wright savings", t.toString());
            return savings.solve(matrix, vansAvailable, capacityPackets, cycleMaxMinutes, allowDrops);
        }
    }

    @Override
    public SolveResult probe(TravelMatrix matrix, int vansAvailable, int capacityPackets, int cycleMaxMinutes) {
        try {
            return orTools.probe(matrix, vansAvailable, capacityPackets, cycleMaxMinutes);
        } catch (Throwable t) {
            log.warn("OR-Tools probe failed ({}); falling back to Clarke–Wright savings", t.toString());
            return savings.probe(matrix, vansAvailable, capacityPackets, cycleMaxMinutes);
        }
    }
}
