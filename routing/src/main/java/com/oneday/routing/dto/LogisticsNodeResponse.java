package com.oneday.routing.dto;

import com.oneday.routing.domain.CityLogisticsNode;

/** Hub / airport coordinate for map markers ({@code GET /routing/nodes/{cityId}}). */
public record LogisticsNodeResponse(
        String kind,
        double lat,
        double lon,
        String name) {

    public static LogisticsNodeResponse from(CityLogisticsNode n) {
        return new LogisticsNodeResponse(n.getKind().name(), n.getLat(), n.getLon(), n.getName());
    }
}
