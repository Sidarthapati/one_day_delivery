package com.oneday.routing.dto;

import java.util.UUID;

/**
 * One edit in a manual override (§10): move the stop identified by {@code stopId} to a different
 * meeting vertex. The override clones the whole plan into a new append-only revision and applies
 * each reassignment to the clone — the original {@code route_plan_stop} rows never mutate (C17).
 */
public record StopReassignment(UUID stopId, UUID newHexVertexId) {}
