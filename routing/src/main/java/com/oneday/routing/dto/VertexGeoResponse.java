package com.oneday.routing.dto;

import java.util.UUID;

/** A meeting vertex with its coordinate — backs the covered/deferred vertex overlays on the map. */
public record VertexGeoResponse(UUID vertexId, Double lat, Double lon) {}
