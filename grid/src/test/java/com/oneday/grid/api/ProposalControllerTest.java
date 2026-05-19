package com.oneday.grid.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.dto.request.ApproveRequest;
import com.oneday.grid.dto.request.IntradayReassignmentRequest;
import com.oneday.grid.dto.request.ProposalRejectRequest;
import com.oneday.grid.dto.request.RegionEditRequest;
import com.oneday.grid.dto.request.TileShareRequest;
import com.oneday.grid.dto.response.IntradayReassignmentResponse;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.dto.response.TileShareResponse;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.ProposalService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ProposalController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class ProposalControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ProposalService proposalService;
    @MockBean GridService gridService;

    final UUID cityId = UUID.randomUUID();
    final UUID proposalId = UUID.randomUUID();
    final UUID daId = UUID.randomUUID();
    final UUID reviewerId = UUID.randomUUID();

    // ---- B2 tests ----------------------------------------------------------

    @Test
    void getProposal_knownId_returns200() throws Exception {
        when(proposalService.getProposal(proposalId)).thenReturn(proposalResponse(proposalId));

        mvc.perform(get("/api/proposals/{id}", proposalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(proposalId.toString()))
                .andExpect(jsonPath("$.status").value("PROPOSED"));
    }

    @Test
    void getProposal_unknownId_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(proposalService.getProposal(unknown))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/proposals/{id}", unknown))
                .andExpect(status().isNotFound());
    }

    @Test
    void listProposals_returns200WithList() throws Exception {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(proposalService.getProposals(eq(cityId), any(LocalDate.class)))
                .thenReturn(List.of(proposalResponse(proposalId)));

        mvc.perform(get("/api/proposals")
                        .param("cityCode", "delhi")
                        .param("date", "2026-05-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(proposalId.toString()));
    }

    @Test
    void approve_returns204() throws Exception {
        String body = objectMapper.writeValueAsString(new ApproveRequest(reviewerId));

        mvc.perform(post("/api/proposals/{id}/approve", proposalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    void reject_returns204() throws Exception {
        String body = objectMapper.writeValueAsString(new ProposalRejectRequest(reviewerId, "bad assignment"));

        mvc.perform(post("/api/proposals/{id}/reject", proposalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    void editRegion_returns204() throws Exception {
        List<UUID> newTiles = List.of(UUID.randomUUID(), UUID.randomUUID());
        String body = objectMapper.writeValueAsString(new RegionEditRequest(newTiles, reviewerId));

        mvc.perform(put("/api/proposals/{id}/regions/{daId}", proposalId, daId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    void intradayReassignment_returns201() throws Exception {
        UUID fromDaId = UUID.randomUUID();
        UUID toDaId = UUID.randomUUID();
        List<UUID> tiles = List.of(UUID.randomUUID());

        IntradayReassignmentResponse response = new IntradayReassignmentResponse(
                proposalId, cityId, fromDaId, toDaId, tiles, ProposalStatus.PROPOSED, Instant.now());
        when(proposalService.requestIntradayReassignment(any(), any(), any(), anyList(), any()))
                .thenReturn(response);

        String body = objectMapper.writeValueAsString(
                new IntradayReassignmentRequest(cityId, fromDaId, toDaId, tiles, reviewerId));

        mvc.perform(post("/api/proposals/intraday-reassignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalId").value(proposalId.toString()));
    }

    @Test
    void approveReassignment_returns204() throws Exception {
        String body = objectMapper.writeValueAsString(new ApproveRequest(reviewerId));

        mvc.perform(post("/api/proposals/{id}/approve-reassignment", proposalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    void tileShare_returns201() throws Exception {
        UUID tileId = UUID.randomUUID();
        TileShareResponse response = new TileShareResponse(proposalId, daId, tileId, ProposalStatus.PROPOSED, Instant.now());
        when(proposalService.requestTileShare(any(), any(), any(), any())).thenReturn(response);

        String body = objectMapper.writeValueAsString(new TileShareRequest(cityId, daId, tileId, reviewerId));

        mvc.perform(post("/api/proposals/tile-share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalId").value(proposalId.toString()));
    }

    @Test
    void approveTileShare_returns204() throws Exception {
        String body = objectMapper.writeValueAsString(new ApproveRequest(reviewerId));

        mvc.perform(post("/api/proposals/{id}/approve-tile-share", proposalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    private ProposalResponse proposalResponse(UUID id) {
        return new ProposalResponse(id, cityId, LocalDate.of(2026, 5, 20),
                ProposalStatus.PROPOSED, ProposalType.NIGHTLY, SolverType.CP_SAT,
                AdjacencySource.OSRM, null, 2, 100.0, List.of(), Instant.now(), null, null, null, List.of());
    }
}
