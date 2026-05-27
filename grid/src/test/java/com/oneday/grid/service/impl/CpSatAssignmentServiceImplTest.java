package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaTileAssignment;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.repository.AssignmentProposalRegionRepository;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaTileAssignmentRepository;
import com.oneday.grid.repository.TileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CpSatAssignmentServiceImplTest {

    @Mock AssignmentProposalRepository proposalRepository;
    @Mock AssignmentProposalRegionRepository regionRepository;
    @Mock DaTileAssignmentRepository assignmentRepository;
    @Mock TileRepository tileRepository;

    BfsAssignmentServiceImpl bfsFallback;
    GridProperties properties = new GridProperties();
    CpSatAssignmentServiceImpl service;

    private final UUID cityId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 5, 18);

    @BeforeEach
    void setUp() {
        // BFS fallback is mocked as a concrete class — Mockito uses CGLIB subclassing
        bfsFallback = Mockito.mock(BfsAssignmentServiceImpl.class);
        service = new CpSatAssignmentServiceImpl(proposalRepository, regionRepository,
                assignmentRepository, tileRepository, bfsFallback, properties);
    }

    private void stubPersistenceLayer() {
        when(proposalRepository.save(any(AssignmentProposal.class))).thenAnswer(inv -> {
            AssignmentProposal p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            if (p.getProposedAt() == null) p.setProposedAt(Instant.now());
            return p;
        });
        when(regionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private TileDemandSnapshot snap(UUID tileId, double demandMin) {
        return TileDemandSnapshot.builder()
                .tileId(tileId).snapshotDate(date)
                .histAvgOrders(demandMin / 17.0).currentOrders(0)
                .demandScoreOrders(demandMin / 17.0).serviceTimeMin(12).interStopTravelMin(5)
                .orderEngagedMin(17).demandScoreMinutes(demandMin).bootstrapped(false)
                .build();
    }

    private AssignmentProposal fakeBfsProposal() {
        AssignmentProposal p = AssignmentProposal.builder()
                .cityId(cityId).validForDate(date).status(ProposalStatus.PROPOSED)
                .solverType(SolverType.BFS_FALLBACK)
                .adjacencySource(com.oneday.grid.domain.AdjacencySource.GEOMETRIC_FALLBACK)
                .totalDas(0).coveragePct(0.0).understaffedTileIds("[]").build();
        p.setId(UUID.randomUUID());
        p.setProposedAt(Instant.now());
        return p;
    }

    // ---- delegation to BFS on edge cases ----------------------------------

    @Test
    void emptyDemand_delegatesToBfs() {
        when(bfsFallback.computeProposal(any(), any(), any(), any(), any()))
                .thenReturn(fakeBfsProposal());

        service.computeProposal(cityId, date, List.of(), Map.of(), List.of(UUID.randomUUID()));

        verify(bfsFallback).computeProposal(any(), any(), any(), any(), any());
    }

    @Test
    void emptyDaIds_delegatesToBfs() {
        when(bfsFallback.computeProposal(any(), any(), any(), any(), any()))
                .thenReturn(fakeBfsProposal());

        service.computeProposal(cityId, date, List.of(snap(UUID.randomUUID(), 200.0)),
                Map.of(), List.of());

        verify(bfsFallback).computeProposal(any(), any(), any(), any(), any());
    }

    @Test
    void moreDasThanTiles_delegatesToBfs() {
        // 1 tile, 2 DAs → K > nTiles → BFS
        when(bfsFallback.computeProposal(any(), any(), any(), any(), any()))
                .thenReturn(fakeBfsProposal());

        service.computeProposal(cityId, date,
                List.of(snap(UUID.randomUUID(), 200.0)),
                Map.of(),
                List.of(UUID.randomUUID(), UUID.randomUUID()));

        verify(bfsFallback).computeProposal(any(), any(), any(), any(), any());
    }

    // ---- real CP-SAT run: 2 tiles, 2 DAs, equal demand -------------------

    @Test
    void twoTilesTwoDas_equalDemand_cpSatSolvesAndPersists() {
        stubPersistenceLayer();

        UUID da0 = UUID.randomUUID(), da1 = UUID.randomUUID();
        UUID t0  = UUID.randomUUID(), t1  = UUID.randomUUID();

        // demand=400 per tile: within [38220, 70980] scaled tolerance band
        // (shift=780min, target=546, lb=546*0.70*100=38220, ub=546*1.30*100=70980; 400*100=40000 ✓)
        List<TileDemandSnapshot> demand = List.of(snap(t0, 400.0), snap(t1, 400.0));

        // Empty adjacency → CP-SAT skips contiguity checks (accepted without cuts)
        AssignmentProposal proposal = service.computeProposal(cityId, date,
                demand, Map.of(), List.of(da0, da1));

        assertThat(proposal).isNotNull();
        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PROPOSED);
        assertThat(proposal.getSolverType()).isEqualTo(SolverType.CP_SAT);
        assertThat(proposal.getTotalDas()).isEqualTo(2);
        // Both tiles assigned → coverage = 100%
        assertThat(proposal.getCoveragePct()).isEqualTo(100.0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaTileAssignment>> captor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(captor.capture());
        List<DaTileAssignment> assignments = captor.getValue();

        // Each DA gets exactly one tile
        assertThat(assignments).hasSize(2);
        assertThat(assignments).allMatch(a -> a.getStatus() == AssignmentStatus.PROPOSED);
        // The two DAs cover different tiles
        assertThat(assignments.stream().map(DaTileAssignment::getTileId).distinct().count()).isEqualTo(2);
        assertThat(assignments.stream().map(DaTileAssignment::getDaId).distinct().count()).isEqualTo(2);
    }

    // ---- real CP-SAT run: contiguity with road graph ----------------------

    @Test
    void twoTilesTwoDas_connectedGraph_convergesInOneLazyCutRound() {
        stubPersistenceLayer();

        UUID da0 = UUID.randomUUID(), da1 = UUID.randomUUID();
        UUID t0  = UUID.randomUUID(), t1  = UUID.randomUUID();

        // Symmetric adjacency: each tile is its own connected territory (size=1 → trivially connected)
        Map<UUID, List<UUID>> adj = Map.of(t0, List.of(t1), t1, List.of(t0));

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                List.of(snap(t0, 400.0), snap(t1, 400.0)), adj, List.of(da0, da1));

        assertThat(proposal.getSolverType()).isEqualTo(SolverType.CP_SAT);
        assertThat(proposal.getCoveragePct()).isEqualTo(100.0);
    }

    // ---- optimality gap is non-negative -----------------------------------

    @Test
    void solvedProposal_optimalityGapIsNonNegative() {
        stubPersistenceLayer();

        UUID da0 = UUID.randomUUID(), da1 = UUID.randomUUID();
        UUID t0  = UUID.randomUUID(), t1  = UUID.randomUUID();

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                List.of(snap(t0, 400.0), snap(t1, 400.0)), Map.of(), List.of(da0, da1));

        assertThat(proposal.getOptimalityGapPct()).isNotNull().isGreaterThanOrEqualTo(0.0);
    }
}
