package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AdjacencySource;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The solver that is actually wired into {@code GridReplanServiceImpl}
 * ({@code @Qualifier("balancedBfsAssignmentService")}). Repositories are mocked; the three
 * phases (seeds → competitive flooding → boundary swaps) run for real.
 */
@ExtendWith(MockitoExtension.class)
class BalancedBfsAssignmentServiceImplTest {

    @Mock AssignmentProposalRepository proposalRepository;
    @Mock AssignmentProposalRegionRepository regionRepository;
    @Mock DaHexAssignmentRepository assignmentRepository;

    GridProperties properties = new GridProperties();   // shift 7→20 = 780 min, target 70 % = 546
    BalancedBfsAssignmentServiceImpl service;

    private final UUID cityId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 10, 26);

    @BeforeEach
    void setUp() {
        service = new BalancedBfsAssignmentServiceImpl(proposalRepository, regionRepository,
                assignmentRepository, properties);
        when(proposalRepository.save(any(AssignmentProposal.class))).thenAnswer(inv -> {
            AssignmentProposal p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            if (p.getProposedAt() == null) p.setProposedAt(Instant.now());
            return p;
        });
        when(regionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- helpers ----------------------------------------------------------

    private HexDemandSnapshot snap(UUID hexId, double demandMin) {
        return HexDemandSnapshot.builder()
                .hexId(hexId).snapshotDate(date)
                .histAvgOrders(demandMin / 17.0).currentOrders(0)
                .demandScoreOrders(demandMin / 17.0).serviceTimeMin(12).interStopTravelMin(5)
                .orderEngagedMin(17).demandScoreMinutes(demandMin).bootstrapped(true)
                .build();
    }

    private static List<UUID> ids(int n) {
        List<UUID> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(UUID.randomUUID());
        return l;
    }

    /** Undirected chain h0 – h1 – … – h(n−1). */
    private static Map<UUID, List<UUID>> chain(List<UUID> h) {
        Map<UUID, List<UUID>> adj = new HashMap<>();
        for (int i = 0; i < h.size(); i++) {
            List<UUID> nbrs = new ArrayList<>();
            if (i > 0) nbrs.add(h.get(i - 1));
            if (i < h.size() - 1) nbrs.add(h.get(i + 1));
            adj.put(h.get(i), nbrs);
        }
        return adj;
    }

    private List<HexDemandSnapshot> demand(List<UUID> h, double each) {
        return h.stream().map(id -> snap(id, each)).toList();
    }

    @SuppressWarnings("unchecked")
    private List<DaHexAssignment> savedAssignments() {
        ArgumentCaptor<List<DaHexAssignment>> c = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(c.capture());
        return c.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<AssignmentProposalRegion> savedRegions() {
        ArgumentCaptor<List<AssignmentProposalRegion>> c = ArgumentCaptor.forClass(List.class);
        verify(regionRepository).saveAll(c.capture());
        return c.getValue();
    }

    private static Map<UUID, Set<UUID>> byDa(List<DaHexAssignment> rows) {
        return rows.stream().collect(Collectors.groupingBy(DaHexAssignment::getDaId,
                Collectors.mapping(DaHexAssignment::getHexId, Collectors.toSet())));
    }

    /** BFS inside the set: true when every hex of the territory is reachable from any other. */
    private static boolean connected(Set<UUID> hexes, Map<UUID, List<UUID>> adj) {
        if (hexes.size() <= 1) return true;
        UUID start = hexes.iterator().next();
        Set<UUID> seen = new HashSet<>(Set.of(start));
        ArrayDeque<UUID> q = new ArrayDeque<>(List.of(start));
        while (!q.isEmpty()) {
            UUID u = q.poll();
            for (UUID v : adj.getOrDefault(u, List.of())) {
                if (hexes.contains(v) && seen.add(v)) q.add(v);
            }
        }
        return seen.size() == hexes.size();
    }

    // ---- positive demand ----------------------------------------------------

    @Test
    void positiveDemand_chainOfFour_twoDas_splitIntoTwoContiguousHalves() {
        List<UUID> h = ids(4);
        Map<UUID, List<UUID>> adj = chain(h);
        List<UUID> das = ids(2);

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                demand(h, 200.0), adj, das);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PROPOSED);
        assertThat(proposal.getSolverType()).isEqualTo(SolverType.BALANCED_BFS);
        assertThat(proposal.getTotalDas()).isEqualTo(2);
        assertThat(proposal.getCoveragePct()).isEqualTo(100.0);
        assertThat(proposal.getOptimalityGapPct()).isNull();

        List<DaHexAssignment> rows = savedAssignments();
        assertThat(rows).hasSize(4).allMatch(a -> a.getStatus() == AssignmentStatus.PROPOSED);

        Map<UUID, Set<UUID>> territories = byDa(rows);
        assertThat(territories).containsOnlyKeys(das.get(0), das.get(1));
        territories.values().forEach(t -> {
            assertThat(t).hasSize(2);                       // 400 min each, target = 800 / 2
            assertThat(connected(t, adj)).isTrue();
        });

        List<AssignmentProposalRegion> regions = savedRegions();
        assertThat(regions).hasSize(2)
                .allMatch(r -> r.getEstimatedDemandMin() == 400.0);
    }

    // ---- zero demand (today's production reality: no shipment_leg_events) --------------

    @Test
    void zeroDemand_balancesByHexCount_noDaOwnsTheWholeCity() {
        List<UUID> h = ids(6);
        Map<UUID, List<UUID>> adj = chain(h);
        List<UUID> das = ids(3);

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                demand(h, 0.0), adj, das);

        assertThat(proposal.getCoveragePct()).isEqualTo(100.0);

        Map<UUID, Set<UUID>> territories = byDa(savedAssignments());
        assertThat(territories).containsOnlyKeys(das.get(0), das.get(1), das.get(2));
        territories.values().forEach(t -> {
            assertThat(t).hasSize(2);                       // 6 hexes / 3 DAs, by count
            assertThat(connected(t, adj)).isTrue();
        });

        // Persisted load is still the real (zero) demand, not the balancing weight.
        assertThat(savedRegions()).hasSize(3)
                .allMatch(r -> r.getEstimatedDemandMin() == 0.0);
    }

    // ---- edge cases ---------------------------------------------------------

    @Test
    void moreDasThanHexes_firstDasKeepTheirSeeds_extraDasStartEmpty() {
        List<UUID> h = ids(2);
        List<UUID> das = ids(3);

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                demand(h, 100.0), chain(h), das);

        assertThat(proposal.getTotalDas()).isEqualTo(3);
        assertThat(proposal.getCoveragePct()).isEqualTo(100.0);

        Map<UUID, Set<UUID>> territories = byDa(savedAssignments());
        // Duplicate seed for DA 2 is skipped: DA 0 and DA 1 keep one hex each, DA 2 gets none.
        assertThat(territories).containsOnlyKeys(das.get(0), das.get(1));
        assertThat(territories.values()).allMatch(t -> t.size() == 1);
        assertThat(savedRegions()).hasSize(2);
    }

    @Test
    void noDas_allHexesUnderstaffed_coverageZero() {
        List<UUID> h = ids(2);

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                demand(h, 100.0), chain(h), List.of());

        assertThat(proposal.getTotalDas()).isEqualTo(0);
        assertThat(proposal.getCoveragePct()).isEqualTo(0.0);
        assertThat(proposal.getUnderstaffedHexIds())
                .contains(h.get(0).toString())
                .contains(h.get(1).toString());
        assertThat(savedAssignments()).isEmpty();
        assertThat(savedRegions()).isEmpty();
    }

    @Test
    void emptyGraph_singleHex_stampsBalancedBfsAndGeometricFallback() {
        UUID hex = UUID.randomUUID();
        UUID da = UUID.randomUUID();

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                List.of(snap(hex, 50.0)), Map.of(), List.of(da));

        assertThat(proposal.getSolverType()).isEqualTo(SolverType.BALANCED_BFS);
        assertThat(proposal.getAdjacencySource()).isEqualTo(AdjacencySource.GEOMETRIC_FALLBACK);
        assertThat(proposal.getOptimalityGapPct()).isNull();

        List<DaHexAssignment> rows = savedAssignments();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDaId()).isEqualTo(da);
        assertThat(rows.get(0).getHexId()).isEqualTo(hex);
    }

    @Test
    void disconnectedPocket_isStillAssigned_nothingLeftUnderstaffed() {
        List<UUID> h = ids(3);
        // h0 – h1 adjacent; h2 is an island with no neighbours.
        Map<UUID, List<UUID>> adj = Map.of(
                h.get(0), List.of(h.get(1)),
                h.get(1), List.of(h.get(0)),
                h.get(2), List.of());
        UUID da = UUID.randomUUID();

        AssignmentProposal proposal = service.computeProposal(cityId, date,
                demand(h, 100.0), adj, List.of(da));

        assertThat(proposal.getCoveragePct()).isEqualTo(100.0);
        assertThat(savedAssignments()).hasSize(3).allMatch(a -> a.getDaId().equals(da));
    }
}
