package com.oneday.routing.service.model;

import java.util.UUID;

/**
 * One stop on a solved van loop. {@code vertexId} is the meeting vertex (null for the hub
 * start/end). {@code arrivalOffsetSeconds} is time-from-loop-start at arrival (PR #3 stamps it to
 * wall-clock when periodising); {@code loadAfter} is the van's onboard packet count after this
 * stop's simultaneous deliver/collect.
 */
public record RouteStop(
        int nodeIndex,
        UUID vertexId,
        long arrivalOffsetSeconds,
        int loadAfter
) {}
