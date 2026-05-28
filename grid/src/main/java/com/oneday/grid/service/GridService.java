package com.oneday.grid.service;

import com.oneday.grid.domain.Grid;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.TileAtResponse;
import com.oneday.grid.dto.response.TileDetailResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GridService {
    ServiceabilityResponse checkServiceability(UUID cityId, String pincode);
    TileAtResponse getTileAt(UUID cityId, double lat, double lon);

    // cityCode maps to classpath:serviceability/{cityCode}.yaml
    void initializeGrid(UUID cityId, String cityCode);

    Grid getGrid(UUID cityId);

    // Resolves cityCode (e.g. "delhi") to the fixed UUID from grid.cities config.
    UUID resolveCityId(String cityCode);

    // All tiles for the city with pre-computed lat/lng bounds and today's demand snapshot.
    List<TileDetailResponse> getTileDetails(UUID cityId, LocalDate date);

    // All grid vertices for the city — used by the map UI to draw tile edges.
    List<GridVertexResponse> getVertices(UUID cityId);

    // Flip a tile's active flag; no-op if already in the desired state.
    void setTileActive(UUID tileId, boolean active);

    // ACTIVE assignments for this city on the given date, scoped to the city's tile set.
    List<AssignmentResponse> getActiveAssignments(UUID cityId, LocalDate date);
}
