package com.oneday.routing.service.model;

import com.oneday.routing.domain.RoutingSolverType;

import java.util.List;
import java.util.UUID;

/**
 * Result of one VRP solve over {@code {hub} ∪ selected vertices} for a single loop. {@code routes}
 * has one entry per van that carries ≥1 stop; {@code solverType} records which engine produced it
 * (OR_TOOLS or the SAVINGS fallback, M6-D-006). {@code feasible} is false when no engine could
 * cover every vertex within capacity + cycle bounds. {@code droppedVertexIds} are the meeting
 * vertices the drop-and-flag solve left unserved because no van could reach them within the cycle.
 */
public record SolveResult(
        List<VanRoute> routes,
        RoutingSolverType solverType,
        boolean feasible,
        List<UUID> droppedVertexIds
) {
    public SolveResult(List<VanRoute> routes, RoutingSolverType solverType, boolean feasible) {
        this(routes, solverType, feasible, List.of());
    }
}
