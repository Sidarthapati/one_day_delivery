package com.oneday.routing.dto;

import com.oneday.routing.domain.RoutePlanStop;

import java.time.LocalTime;
import java.util.UUID;

/** One ordered stop + ETAs ({@code GET /routing/plans/{cityId}/vans/{vanId}/stops}). */
public record RoutePlanStopResponse(
        UUID stopId,
        UUID vanId,
        int loopIndex,
        int stopSeq,
        String nodeKind,
        UUID hexVertexId,
        LocalTime plannedArrival,
        LocalTime plannedDeparture,
        int deliverQty,
        int collectQty,
        int loadAfter) {

    public static RoutePlanStopResponse from(RoutePlanStop s) {
        return new RoutePlanStopResponse(
                s.getId(), s.getVanId(), s.getLoopIndex(), s.getStopSeq(),
                s.getNodeKind() != null ? s.getNodeKind().name() : null,
                s.getHexVertexId(), s.getPlannedArrival(), s.getPlannedDeparture(),
                s.getDeliverQty(), s.getCollectQty(), s.getLoadAfter());
    }
}
