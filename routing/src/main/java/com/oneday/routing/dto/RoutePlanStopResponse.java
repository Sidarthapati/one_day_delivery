package com.oneday.routing.dto;

import com.oneday.routing.domain.RoutePlanStop;

import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/**
 * One ordered stop + ETAs ({@code GET /routing/plans/{cityId}/vans/{vanId}/stops}). {@code lat}/{@code
 * lon} are the meeting-vertex coordinates (the plan stores only {@code hexVertexId}), joined to the
 * grid server-side so the van app can deep-link navigation to each stop — null if unresolvable.
 */
public record RoutePlanStopResponse(
        UUID stopId,
        UUID vanId,
        int loopIndex,
        int stopSeq,
        String nodeKind,
        UUID hexVertexId,
        Double lat,
        Double lon,
        LocalTime plannedArrival,
        LocalTime plannedDeparture,
        int deliverQty,
        int collectQty,
        int loadAfter) {

    public static RoutePlanStopResponse from(RoutePlanStop s) {
        return from(s, Map.of());
    }

    public static RoutePlanStopResponse from(RoutePlanStop s, Map<UUID, double[]> coords) {
        double[] ll = s.getHexVertexId() != null ? coords.get(s.getHexVertexId()) : null;
        return new RoutePlanStopResponse(
                s.getId(), s.getVanId(), s.getLoopIndex(), s.getStopSeq(),
                s.getNodeKind() != null ? s.getNodeKind().name() : null,
                s.getHexVertexId(),
                ll != null ? ll[0] : null,
                ll != null ? ll[1] : null,
                s.getPlannedArrival(), s.getPlannedDeparture(),
                s.getDeliverQty(), s.getCollectQty(), s.getLoadAfter());
    }
}
