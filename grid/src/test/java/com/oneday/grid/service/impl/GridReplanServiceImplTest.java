package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.domain.HexDemandSnapshot;
import com.oneday.grid.domain.HexTravelTime;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.repository.HexTravelTimeRepository;
import com.oneday.grid.service.AssignmentService;
import com.oneday.grid.service.DemandScoringService;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.ProposalService;
import com.uber.h3core.H3Core;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GridReplanServiceImplTest {

    @Mock GridService gridService;
    @Mock HexRepository hexRepository;
    @Mock HexTravelTimeRepository travelTimeRepository;
    @Mock DemandScoringService demandScoringService;
    @Mock AssignmentService cpSatAssignmentService;
    @Mock AssignmentProposalRepository proposalRepository;
    @Mock ProposalService proposalService;
    @Mock GridProperties properties;
    @Mock H3Core h3Core;
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
        // stub h3Core.gridDisk so geometric fallback path doesn't NPE
        lenient().when(h3Core.gridDisk(anyLong(), any(Integer.class))).thenReturn(List.of());

        service = new GridReplanServiceImpl(gridService, hexRepository, travelTimeRepository,
                demandScoringService, cpSatAssignmentService, proposalRepository, proposalService, properties, h3Core);
    }

    // ---- helpers -----------------------------------------------------------

    private HexDemandSnapshot demandSnapshot(UUID hexId) {
        return HexDemandSnapshot.builder()
                .hexId(hexId)
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

    private HexTravelTime freshRow(UUID from, UUID to) {
        return HexTravelTime.builder()
                .h3GridId(gridId).fromHexId(from).toHexId(to)
                .travelTimeSeconds(300).computedAt(Instant.now()).build();
    }

    private HexTravelTime staleRow(UUID from, UUID to) {
        return HexTravelTime.builder()
                .h3GridId(gridId).fromHexId(from).toHexId(to)
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
        UUID hexId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(hexId)));
        when(travelTimeRepository.findByH3GridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of(freshRow(hexId, UUID.randomUUID())));

        AssignmentProposal p = proposal(AdjacencySource.OSRM);
        when(cpSatAssignmentService.computeProposal(eq(cityId), eq(date), anyList(), anyMap(), anyList()))
                .thenReturn(p);
        when(proposalService.getProposal(p.getId())).thenReturn(responseFor(p));

        ProposalResponse result = service.replan(cityId, date, List.of(UUID.randomUUID()));

        assertThat(result).isNotNull();
        verify(hexRepository, never()).findByH3GridIdAndActiveTrue(any());
        verify(proposalRepository, never()).save(any());
    }

    @Test
    void replan_withStaleMatrix_usesGeometricFallback() {
        UUID hexId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(hexId)));
        when(travelTimeRepository.findByH3GridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of(staleRow(hexId, UUID.randomUUID())));

        Hex hex = Hex.builder().h3GridId(gridId).h3Index(0L).active(true).build();
        hex.setId(hexId);
        when(hexRepository.findByH3GridIdAndActiveTrue(gridId)).thenReturn(List.of(hex));

        AssignmentProposal p = proposal(AdjacencySource.OSRM);
        when(cpSatAssignmentService.computeProposal(any(), any(), anyList(), anyMap(), anyList()))
                .thenReturn(p);
        when(proposalService.getProposal(p.getId())).thenReturn(responseFor(p));

        service.replan(cityId, date, List.of(UUID.randomUUID()));

        verify(hexRepository).findByH3GridIdAndActiveTrue(gridId);
        assertThat(p.getAdjacencySource()).isEqualTo(AdjacencySource.GEOMETRIC_FALLBACK);
        verify(proposalRepository).save(p);
    }

    @Test
    void replan_withNoMatrix_usesGeometricFallback() {
        UUID hexId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(hexId)));
        when(travelTimeRepository.findByH3GridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of());

        Hex hex = Hex.builder().h3GridId(gridId).h3Index(0L).active(true).build();
        hex.setId(hexId);
        when(hexRepository.findByH3GridIdAndActiveTrue(gridId)).thenReturn(List.of(hex));

        AssignmentProposal p = proposal(AdjacencySource.OSRM);
        when(cpSatAssignmentService.computeProposal(any(), any(), anyList(), anyMap(), anyList()))
                .thenReturn(p);
        when(proposalService.getProposal(p.getId())).thenReturn(responseFor(p));

        service.replan(cityId, date, List.of(UUID.randomUUID()));

        verify(hexRepository).findByH3GridIdAndActiveTrue(gridId);
        assertThat(p.getAdjacencySource()).isEqualTo(AdjacencySource.GEOMETRIC_FALLBACK);
    }

    @Test
    void replan_withNoDas_callsComputeProposalWithEmptyList() {
        UUID hexId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(hexId)));
        when(travelTimeRepository.findByH3GridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of(freshRow(hexId, UUID.randomUUID())));

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
        UUID hexId = UUID.randomUUID();
        when(demandScoringService.computeAndPersistDemand(cityId, date))
                .thenReturn(List.of(demandSnapshot(hexId)));
        when(travelTimeRepository.findByH3GridIdAndTravelTimeSecondsLessThanEqual(gridId, 600))
                .thenReturn(List.of(freshRow(hexId, UUID.randomUUID())));

        AssignmentProposal p = proposal(AdjacencySource.OSRM);
        when(cpSatAssignmentService.computeProposal(any(), any(), anyList(), anyMap(), anyList()))
                .thenReturn(p);

        ProposalResponse expected = responseFor(p);
        when(proposalService.getProposal(p.getId())).thenReturn(expected);

        ProposalResponse actual = service.replan(cityId, date, List.of(UUID.randomUUID()));

        assertThat(actual).isSameAs(expected);
    }
}
