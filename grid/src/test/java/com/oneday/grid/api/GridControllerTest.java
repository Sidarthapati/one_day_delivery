package com.oneday.grid.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.dto.request.ReplanRequest;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.TileAtResponse;
import com.oneday.grid.dto.response.TileDetailResponse;
import com.oneday.grid.dto.response.TileLoadScoreResponse;
import com.oneday.grid.service.GridReplanService;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.IntradayLoadScoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = GridController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class GridControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean GridService gridService;
    @MockBean GridReplanService gridReplanService;
    @MockBean IntradayLoadScoreService loadScoreService;

    final UUID cityId = UUID.randomUUID();
    final UUID tileId = UUID.randomUUID();

    // ---- B1 tests ----------------------------------------------------------

    @Test
    void getTiles_knownCity_returns200WithTileList() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(gridService.getTileDetails(eq(cityId), any(LocalDate.class)))
                .thenReturn(List.of(new TileDetailResponse(tileId, "872be10cafffffff", true, 28.5, 77.0, 5.0, 75.0, false)));

        mvc.perform(get("/api/grid/delhi/tiles").param("date", "2026-05-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].h3Index").value("872be10cafffffff"))
                .andExpect(jsonPath("$[0].centerLat").exists());
    }

    @Test
    void getTiles_unknownCity_returns404() throws Exception {
        when(gridService.resolveCityId("atlantis"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown cityCode: atlantis"));

        mvc.perform(get("/api/grid/atlantis/tiles"))
                .andExpect(status().isNotFound());
    }

    @Test
    void setTileActive_validRequest_returns204() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);

        mvc.perform(patch("/api/grid/delhi/tiles/{id}/active", tileId).param("active", "false"))
                .andExpect(status().isNoContent());

        verify(gridService).setTileActive(tileId, false);
    }

    @Test
    void getLoadScore_knownTile_returns200WithSeverity() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(loadScoreService.getLoadScore(eq(tileId), any(LocalDate.class)))
                .thenReturn(new TileLoadScoreResponse(tileId, LocalDate.of(2026, 5, 20), 2, 1.3, "OK"));

        mvc.perform(get("/api/grid/delhi/tiles/{id}/load-score", tileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("OK"));
    }

    @Test
    void getVertices_returns200() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(gridService.getVertices(cityId))
                .thenReturn(List.of(new GridVertexResponse(UUID.randomUUID(), 28.5, 77.0)));

        mvc.perform(get("/api/grid/delhi/vertices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getAssignments_returns200() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(gridService.getActiveAssignments(eq(cityId), any(LocalDate.class))).thenReturn(List.of());

        mvc.perform(get("/api/grid/delhi/assignments").param("date", "2026-05-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getServiceability_knownPincode_returnsServiceableTrue() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(gridService.checkServiceability(cityId, "110001"))
                .thenReturn(new ServiceabilityResponse(cityId, "110001", true, tileId));

        mvc.perform(get("/api/grid/delhi/serviceability").param("pincode", "110001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceable").value(true));
    }

    @Test
    void getServiceability_unknownPincode_returnsServiceableFalse() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(gridService.checkServiceability(cityId, "999999"))
                .thenReturn(new ServiceabilityResponse(cityId, "999999", false, null));

        mvc.perform(get("/api/grid/delhi/serviceability").param("pincode", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceable").value(false));
    }

    @Test
    void getTileAt_validCoords_returns200() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(gridService.getTileAt(cityId, 28.6, 77.2))
                .thenReturn(new TileAtResponse(tileId, "872be10cafffffff", true));

        mvc.perform(get("/api/grid/delhi/tile-at").param("lat", "28.6").param("lon", "77.2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hexId").value(tileId.toString()))
                .andExpect(jsonPath("$.h3Index").value("872be10cafffffff"));
    }

    @Test
    void replan_validBody_returns201() throws Exception {
        UUID proposalId = UUID.randomUUID();
        UUID daId = UUID.randomUUID();
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(gridReplanService.replan(eq(cityId), any(LocalDate.class), anyList()))
                .thenReturn(proposalResponse(proposalId));

        String body = objectMapper.writeValueAsString(new ReplanRequest(List.of(daId), LocalDate.of(2026, 5, 21)));

        mvc.perform(post("/api/grid/delhi/replan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(proposalId.toString()));
    }

    @Test
    void adminInit_returns201() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);

        mvc.perform(post("/api/grid/admin/init").param("cityCode", "delhi"))
                .andExpect(status().isCreated());

        verify(gridService).initializeGrid(cityId, "delhi");
    }

    private ProposalResponse proposalResponse(UUID id) {
        return new ProposalResponse(id, cityId, LocalDate.of(2026, 5, 21),
                ProposalStatus.PROPOSED, ProposalType.NIGHTLY, SolverType.CP_SAT,
                AdjacencySource.OSRM, null, 1, 100.0, List.of(), Instant.now(), null, null, null, List.of());
    }
}
