package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.AssignmentProposalRegion;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.HexDemandSnapshot;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.repository.AssignmentProposalRegionRepository;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
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
class BfsAssignmentServiceImplTest {

    @Mock AssignmentProposalRepository proposalRepository;
    @Mock AssignmentProposalRegionRepository regionRepository;
    @Mock DaHexAssignmentRepository assignmentRepository;

    GridProperties properties = new GridProperties();
    BfsAssignmentServiceImpl service;

    private final UUID cityId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 5, 18);

    @BeforeEach
    void setUp() {
        service = new BfsAssignmentServiceImpl(proposalRepository, regionRepository,
                assignmentRepository, properties);
        // shift 7→20 = 780 min, target=70%=546, max=90%=702
        stubProposalSave();
        when(regionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubProposalSave() {
        when(proposalRepository.save(any(AssignmentProposal.class))).thenAnswer(inv -> {
            AssignmentProposal p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            if (p.getProposedAt() == null) p.setProposedAt(Instant.now());
            return p;
        });
    }

    private HexDemandSnapshot snap(UUID hexId, double demandMin) {
        return HexDemandSnapshot.builder()
                .hexId(hexId).snapshotDate(date)
                .histAvgOrders(demandMin / 17.0).currentOrders(0)
                .demandScoreOrders(demandMin / 17.0).serviceTimeMin(12).interStopTravelMin(5)
                .orderEngagedMin(17).demandScoreMinutes(demandMin).bootstrapped(true)
                .build();
    }

    // ---- single DA, single hex -------------------------------------------

    @Test
    void singleDaSingleTile_tileAssignedToDA() {
        UUID daId = UUID.randomUUID();
        UUID hexId = UUID.randomUUID();

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                List.of(snap(hexId, 200.0)), Map.of(), List.of(daId));

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PROPOSED);
        assertThat(proposal.getSolverType()).isEqualTo(SolverType.BFS_FALLBACK);
        assertThat(proposal.getTotalDas()).isEqualTo(1);
        assertThat(proposal.getCoveragePct()).isEqualTo(100.0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaHexAssignment>> captor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(captor.capture());
        List<DaHexAssignment> saved = captor.getValue();

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getDaId()).isEqualTo(daId);
        assertThat(saved.get(0).getHexId()).isEqualTo(hexId);
        assertThat(saved.get(0).getStatus()).isEqualTo(AssignmentStatus.PROPOSED);
    }

    // ---- two adjacent hexes, one DA ---------------------------------------

    @Test
    void twoAdjacentTiles_oneDa_bothAssigned() {
        UUID daId = UUID.randomUUID();
        UUID tA = UUID.randomUUID();
        UUID tB = UUID.randomUUID();
        // demand: A=300, B=200 (total=500 < max=702)
        Map<UUID, List<UUID>> adj = Map.of(tA, List.of(tB), tB, List.of(tA));

        service.computeProposal(cityId, date,
                List.of(snap(tA, 300.0), snap(tB, 200.0)), adj, List.of(daId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaHexAssignment>> captor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(captor.capture());

        assertThat(captor.getValue()).hasSize(2)
                .allMatch(a -> a.getDaId().equals(daId))
                .allMatch(a -> a.getStatus() == AssignmentStatus.PROPOSED);
    }

    // ---- dynamic target load: all hexes partitioned even when total > shift capacity ----

    @Test
    void singleDa_twoadjacentTiles_bothAssigned() {
        // daTargetLoad = totalDemand / K = 800 / 1 = 800.
        // No hard ceiling — BFS assigns until load >= target, so both hexes are assigned.
        UUID daId = UUID.randomUUID();
        UUID tA = UUID.randomUUID();
        UUID tB = UUID.randomUUID();
        Map<UUID, List<UUID>> adj = Map.of(tA, List.of(tB), tB, List.of(tA));

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                List.of(snap(tA, 500.0), snap(tB, 300.0)), adj, List.of(daId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaHexAssignment>> assignCaptor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(assignCaptor.capture());
        // Both hexes assigned to the single DA
        assertThat(assignCaptor.getValue()).hasSize(2);
        assertThat(proposal.getCoveragePct()).isEqualTo(100.0);
    }

    // ---- contiguity: asymmetric graph discards non-adjacent hex ----------

    @Test
    void asymmetricAdjacency_nonAdjacentTileSkipped() {
        UUID daId = UUID.randomUUID();
        UUID tA = UUID.randomUUID();
        UUID tB = UUID.randomUUID();
        // Only A→B edge; B has no outgoing edge back to A
        // BFS seeds on A (higher demand). Adds B to frontier.
        // Contiguity check for B: adjacencyGraph.get(B)=[] → not adjacent to {A} → skip.
        Map<UUID, List<UUID>> adj = Map.of(tA, List.of(tB));

        service.computeProposal(cityId, date,
                List.of(snap(tA, 400.0), snap(tB, 200.0)), adj, List.of(daId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaHexAssignment>> captor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(captor.capture());

        // Only A should be assigned; B fails contiguity
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getHexId()).isEqualTo(tA);
    }

    // ---- no DAs → all hexes understaffed ----------------------------------

    @Test
    void zeroDas_allTilesUnderstaffed() {
        UUID hexId = UUID.randomUUID();

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                List.of(snap(hexId, 200.0)), Map.of(), List.of());

        assertThat(proposal.getTotalDas()).isEqualTo(0);
        assertThat(proposal.getCoveragePct()).isEqualTo(0.0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaHexAssignment>> captor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    // ---- empty demand → empty proposal ------------------------------------

    @Test
    void emptyDemand_emptyProposalCreated() {
        AssignmentProposal proposal = service.computeProposal(cityId, date,
                List.of(), Map.of(), List.of(UUID.randomUUID()));

        assertThat(proposal.getTotalDas()).isEqualTo(0);
        assertThat(proposal.getCoveragePct()).isEqualTo(0.0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaHexAssignment>> captor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    // ---- two DAs, four hexes, balanced distribution ----------------------

    @Test
    void twoDasFourTiles_tilesDistributedBetweenDas() {
        UUID da0 = UUID.randomUUID(), da1 = UUID.randomUUID();
        UUID t0 = UUID.randomUUID(), t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID(), t3 = UUID.randomUUID();
        // Chain: t0-t1-t2-t3, demand=200 each (total=800)
        Map<UUID, List<UUID>> adj = Map.of(
                t0, List.of(t1), t1, List.of(t0, t2),
                t2, List.of(t1, t3), t3, List.of(t2)
        );

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                List.of(snap(t0, 200.0), snap(t1, 200.0), snap(t2, 200.0), snap(t3, 200.0)),
                adj, List.of(da0, da1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaHexAssignment>> assignCaptor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(assignCaptor.capture());
        // All 4 hexes assigned
        assertThat(assignCaptor.getValue()).hasSize(4);
        assertThat(proposal.getCoveragePct()).isEqualTo(100.0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssignmentProposalRegion>> regionCaptor = ArgumentCaptor.forClass(List.class);
        verify(regionRepository).saveAll(regionCaptor.capture());
        assertThat(regionCaptor.getValue()).hasSize(2); // one region per DA
    }
}
