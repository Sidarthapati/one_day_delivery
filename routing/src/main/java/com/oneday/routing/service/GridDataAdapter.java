package com.oneday.routing.service;

import com.oneday.grid.dto.response.DaTerritoryResponse;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.dto.response.TerritoryHexResponse;
import com.oneday.grid.service.GridService;
import com.oneday.routing.service.model.DaTerritory;
import com.oneday.routing.service.model.MeetingVertex;
import com.oneday.routing.service.model.TerritoryHex;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * M6's single seam onto M3 (§6). Imports only grid's <b>public service interface</b> and its
 * response DTOs — never {@code grid.domain} / {@code grid.repository} (the cross-module rule; an
 * arch test enforces it). Maps grid's territory DTOs onto M6's own {@link DaTerritory} model so
 * nothing else in routing depends on grid types.
 */
@Service
public class GridDataAdapter {

    private final GridService gridService;

    GridDataAdapter(GridService gridService) {
        this.gridService = gridService;
    }

    /** DA → hexes (+ demand) → corner vertices for the city on the date (ACTIVE assignments only). */
    public List<DaTerritory> getDaTerritories(UUID cityId, LocalDate date) {
        return gridService.getDaTerritories(cityId, date).stream()
                .map(GridDataAdapter::toTerritory)
                .toList();
    }

    /**
     * vertexId → {lat, lon} for the whole city grid. The demo's route view resolves each stop's
     * {@code hexVertexId} to a coordinate to draw van polylines (stops carry only the vertex id).
     */
    public Map<UUID, double[]> vertexCoords(UUID cityId) {
        return gridService.getVertices(cityId).stream()
                .collect(Collectors.toMap(GridVertexResponse::id,
                        v -> new double[]{v.lat(), v.lon()}, (a, b) -> a));
    }

    private static DaTerritory toTerritory(DaTerritoryResponse dto) {
        List<TerritoryHex> hexes = dto.hexes().stream()
                .map(GridDataAdapter::toHex)
                .toList();
        return new DaTerritory(dto.daId(), hexes);
    }

    private static TerritoryHex toHex(TerritoryHexResponse dto) {
        List<MeetingVertex> vertices = dto.vertices().stream()
                .map(GridDataAdapter::toVertex)
                .toList();
        return new TerritoryHex(dto.hexId(), dto.h3Index(),
                dto.demandScoreOrders(), dto.serviceTimeMin(), vertices);
    }

    private static MeetingVertex toVertex(GridVertexResponse dto) {
        return new MeetingVertex(dto.id(), dto.lat(), dto.lon());
    }
}
