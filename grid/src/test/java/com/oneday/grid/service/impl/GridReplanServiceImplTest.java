package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.domain.TileTravelTime;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.repository.TileTravelTimeRepository;
import com.oneday.grid.service.AssignmentService;
import com.oneday.grid.service.DemandScoringService;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.ProposalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GridReplanServiceImplTest {

    @Mock GridService gridService;
    @Mock TileRepository tileRepository;
    @Mock TileTravelTimeRepository travelTimeRepository;
    @Mock DemandScoringService demandScoringService;
    @Mock AssignmentService cpSatAssignmentService;
    @Mock AssignmentProposalRepository proposalRepository;
    @Mock ProposalService proposalService;
    @Mock GridProperties properties;
    @Mock Grid grid;

    GridReplanServiceImpl service;

    final UUID cityId = UUID.randomUUID();
    final UUID gridId = UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 5, 21);

    @BeforeEach
    void setUp() {
        GridProperties.Shift shift = new GridProperties.Shift();
        GridProperties.Da da = new GridProperties.Da();
        GridProperties.Osrm osrm = new GridProperties.Osrm();

        lenient().when(properties.getShift()).thenReturn(shift);
        lenient().when(properties.getDa()).thenReturn(da);
        lenient().when(properties.getOsrm()).thenReturn(osrm);
        lenient().when(grid.getId()).thenReturn(gridId);
        lenient().when(gridService.getGrid(cityId)).thenReturn(grid);

        service = new GridReplanServiceImpl(gridService, tileRepository, travelTimeRepository,
                demandScoringService, cpSatAssignmentService, proposalRepository, proposalService, properties);
    }

    // ---- helpers -----------------------------------------------------------

    private TileDemandSnapshot demandSnapshot(UUID tileId) {
        return TileDemandSnapshot.builder()
                .tileId(tileId)
                .snapshotDate(date)
                .histAvgOrders(5.0)
                .currentOrders(3)
                .demandScoreOrders(4.4)
                .serviceTimeMin(12.0)
                .interStopTravelMin(5.0)
                .orderEngagedMin(17.0)
                .demandScoreMinutes(74.8)
                .build();
    }

    private TileTravelTime freshRow(UUID from, UUID to) {
        return TileTravelTime.builder()
                .gridId(gridId).fromTileId(from).toTileId(to)
                .travelTimeSeconds(300).computedAt(Instant.now()).build();
    }

    private TileTravelTime staleRow(UUID from, UUID to) {
        return TileTravelTime.builder()
                .gridId(gridId).fromTileId(from).toTileId(to)
                .travelTimeSeconds(300)
                .computedAt(Instant.now().minus(50, ChronoUnit.DAYS)).build();
    }

    private AssignmentProposal proposal(AdjacencySource source) {
        AssignmentProposal p = AssignmentProposal.builder()
                .cityId(cityId).validForDate(date)
                .adjacencySource(source).solverType(SolverType.CP_SAT)
                .status(ProposalStatus.PROPOSED).totalDas(1).build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private ProposalResponse responseFor(AssignmentProposal p) {
        return new ProposalResponse(p.getId(), cityId, date, ProposalStatus.PROPOSED,
                ProposalType.NIGHTLY, SolverType.CP_SAT, p.getAdjacencySource(),
                null, p.getTotalDas(), 100.0, List.of(), Instant.now(), null, null, null, List.of());
    }

    // ---- A1 tests ----------------------------------------------------------

    @Test
    void replan_withFreshMatrix_usesCpSat() {
        UUID tileId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(tileId)));
        when(travelTimeRepository.findByGridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of(freshRow(tileId, UUID.randomUUID())));

        AssignmentProposal p = proposal(AdjacencySource.OSRM);
        when(cpSatAssignmentService.computeProposal(eq(cityId), eq(date), anyList(), anyMap(), anyList()))
                .thenReturn(p);
        when(proposalService.getProposal(p.getId())).thenReturn(responseFor(p));

        ProposalResponse result = service.replan(cityId, date, List.of(UUID.randomUUID()));

        assertThat(result).isNotNull();
        verify(tileRepository, never()).findByGridIdAndActiveTrue(any());
        verify(proposalRepository, never()).save(any());
    }

    @Test
    void replan_withStaleMatrix_usesGeometricFallback() {
        UUID tileId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(tileId)));
        when(travelTimeRepository.findByGridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of(staleRow(tileId, UUID.randomUUID())));

        Tile tile = Tile.builder().gridId(gridId).rowIdx(0).colIdx(0).active(true).build();
        tile.setId(tileId);
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of(tile));

        AssignmentProposal p = proposal(AdjacencySource.OSRM);
        when(cpSatAssignmentService.computeProposal(any(), any(), anyList(), anyMap(), anyList()))
                .thenReturn(p);
        when(proposalService.getProposal(p.getId())).thenReturn(responseFor(p));

        service.replan(cityId, date, List.of(UUID.randomUUID()));

        verify(tileRepository).findByGridIdAndActiveTrue(gridId);
        assertThat(p.getAdjacencySource()).isEqualTo(AdjacencySource.GEOMETRIC_FALLBACK);
        verify(proposalRepository).save(p);
    }

    @Test
    void replan_withNoMatrix_usesGeometricFallback() {
        UUID tileId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(tileId)));
        when(travelTimeRepository.findByGridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of());

        Tile tile = Tile.builder().gridId(gridId).rowIdx(0).colIdx(0).active(true).build();
        tile.setId(tileId);
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of(tile));

        AssignmentProposal p = proposal(AdjacencySource.OSRM);
        when(cpSatAssignmentService.computeProposal(any(), any(), anyList(), anyMap(), anyList()))
                .thenReturn(p);
        when(proposalService.getProposal(p.getId())).thenReturn(responseFor(p));

        service.replan(cityId, date, List.of(UUID.randomUUID()));

        verify(tileRepository).findByGridIdAndActiveTrue(gridId);
        assertThat(p.getAdjacencySource()).isEqualTo(AdjacencySource.GEOMETRIC_FALLBACK);
    }

    @Test
    void replan_withNoDas_callsComputeProposalWithEmptyList() {
        UUID tileId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(tileId)));
        when(travelTimeRepository.findByGridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of(freshRow(tileId, UUID.randomUUID())));

        AssignmentProposal p = proposal(AdjacencySource.OSRM);
        p.setTotalDas(0);
        when(cpSatAssignmentService.computeProposal(eq(cityId), eq(date), anyList(), anyMap(), eq(List.of())))
                .thenReturn(p);
        when(proposalService.getProposal(p.getId())).thenReturn(responseFor(p));

        ProposalResponse result = service.replan(cityId, date, List.of());

        verify(cpSatAssignmentService).computeProposal(eq(cityId), eq(date), anyList(), anyMap(), eq(List.of()));
        assertThat(result).isNotNull();
    }

    @Test
    void replan_returnsProposalResponseFromProposalService() {
        UUID tileId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(tileId)));
        when(travelTimeRepository.findByGridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of(freshRow(tileId, UUID.randomUUID())));

        AssignmentProposal p = proposal(AdjacencySource.OSRM);
        when(cpSatAssignmentService.computeProposal(any(), any(), anyList(), anyMap(), anyList()))
                .thenReturn(p);

        ProposalResponse expected = responseFor(p);
        when(proposalService.getProposal(p.getId())).thenReturn(expected);

        ProposalResponse actual = service.replan(cityId, date, List.of(UUID.randomUUID()));

        assertThat(actual).isSameAs(expected);
    }
}
