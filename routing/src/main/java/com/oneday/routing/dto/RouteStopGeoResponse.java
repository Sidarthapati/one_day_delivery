package com.oneday.routing.dto;

import com.oneday.routing.domain.RoutePlanStop;

import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/**
 * A stop enriched with its meeting-vertex coordinate ({@code GET /routing/plans/{cityId}/stops}).
 * The plan stores only {@code hexVertexId}; the demo route view needs lat/lon to draw van polylines,
 * so this endpoint joins each stop to the grid vertex coordinate server-side (one call per plan).
 */
public record RouteStopGeoResponse(
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

    public static RouteStopGeoResponse from(RoutePlanStop s, Map<UUID, double[]> coords) {
        double[] ll = s.getHexVertexId() != null ? coords.get(s.getHexVertexId()) : null;
        return new RouteStopGeoResponse(
                s.getId(), s.getVanId(), s.getLoopIndex(), s.getStopSeq(),
                s.getNodeKind() != null ? s.getNodeKind().name() : null,
                s.getHexVertexId(),
                ll != null ? ll[0] : null,
                ll != null ? ll[1] : null,
                s.getPlannedArrival(), s.getPlannedDeparture(),
                s.getDeliverQty(), s.getCollectQty(), s.getLoadAfter());
    }
}
