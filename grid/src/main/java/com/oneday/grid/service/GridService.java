package com.oneday.grid.service;

import com.oneday.grid.domain.Grid;
import com.oneday.grid.dto.response.AbsenceReassignmentPlan;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.dto.response.DaTerritoryResponse;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.ServiceableAtResponse;
import com.oneday.grid.dto.response.TileAtResponse;
import com.oneday.grid.dto.response.TileDetailResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface GridService {
    ServiceabilityResponse checkServiceability(UUID cityId, String pincode);
    TileAtResponse getTileAt(UUID cityId, double lat, double lon);

    // Resolves a WGS84 point to whichever seeded city grid contains it (used by the
    // booking map UI — the caller need not know the city up front).
    ServiceableAtResponse serviceableAt(double lat, double lon);

    // cityCode maps to classpath:serviceability/{cityCode}.yaml
    void initializeGrid(UUID cityId, String cityCode);

    Grid getGrid(UUID cityId);

    // Resolves cityCode (e.g. "delhi") to the fixed UUID from grid.cities config.
    UUID resolveCityId(String cityCode);

    // Reverse of resolveCityId: the grid.cities code (e.g. "delhi") for a city UUID, or null.
    String resolveCityCode(UUID cityId);

    // All tiles for the city with pre-computed lat/lng bounds and today's demand snapshot.
    List<TileDetailResponse> getTileDetails(UUID cityId, LocalDate date);

    // All grid vertices for the city — used by the map UI to draw tile edges.
    List<GridVertexResponse> getVertices(UUID cityId);

    // Flip a tile's active flag; no-op if already in the desired state.
    void setTileActive(UUID tileId, boolean active);

    // ACTIVE assignments for this city on the given date, scoped to the city's tile set.
    List<AssignmentResponse> getActiveAssignments(UUID cityId, LocalDate date);

    // DA territories for the date — DA → hexes (+ demand) → corner vertices. M6's planning input
    // (§6); built from ACTIVE da_hex_assignment rows joined to the date's demand snapshot.
    List<DaTerritoryResponse> getDaTerritories(UUID cityId, LocalDate date);

    // Midday DA absence (M5-triggered): split the absent DAs' hexes among their territory-neighbors.
    // plan* is compute-only (preview); apply* writes + approves the INTRADAY_OVERRIDE and returns the
    // committed split so M5 can move the matching tasks. {@code inShiftDaIds} is the absent DA's shift
    // roster — the split is scoped to it so the other shift's same-date plan doesn't leak in.
    AbsenceReassignmentPlan planAbsenceReassignment(UUID cityId, List<UUID> absentDaIds, LocalDate date,
                                                    Set<UUID> inShiftDaIds);

    AbsenceReassignmentPlan applyAbsenceReassignment(UUID cityId, List<UUID> absentDaIds, LocalDate date,
                                                     Set<UUID> inShiftDaIds, UUID reviewerId);
}
