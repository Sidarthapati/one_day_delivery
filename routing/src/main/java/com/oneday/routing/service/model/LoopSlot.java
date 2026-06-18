package com.oneday.routing.service.model;

import java.time.Instant;
import java.util.UUID;

// One loop of a van at a DA's vertex: when the van arrives (deliver) and when its load reaches the hub (collect).
public record LoopSlot(
        int loopIndex,
        UUID vanId,
        UUID vertexId,
        int stopSeq,
        Instant vanArrival,
        Instant hubReturn) {
}
