package com.oneday.routing.service.model;

import com.oneday.routing.domain.RoutingSolverType;

import java.util.List;

/**
 * Result of one VRP solve over {@code {hub} ∪ selected vertices} for a single loop. {@code routes}
 * has one entry per van that carries ≥1 stop; {@code solverType} records which engine produced it
 * (OR_TOOLS or the SAVINGS fallback, M6-D-006). {@code feasible} is false when no engine could
 * cover every vertex within capacity + cycle bounds.
 */
public record SolveResult(
        List<VanRoute> routes,
        RoutingSolverType solverType,
        boolean feasible
) {}
