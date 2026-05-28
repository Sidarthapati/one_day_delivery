package com.oneday.grid.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.oneday.grid.service.AssignmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Qualifier("balancedBfsAssignmentService")
class BalancedBfsAssignmentServiceImpl implements AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(BalancedBfsAssignmentServiceImpl.class);
    private static final int MAX_SWAP_PASSES = 300;

    private final AssignmentProposalRepository proposalRepository;
    private final AssignmentProposalRegionRepository regionRepository;
    private final DaHexAssignmentRepository assignmentRepository;
    private final GridProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    BalancedBfsAssignmentServiceImpl(AssignmentProposalRepository proposalRepository,
                                     AssignmentProposalRegionRepository regionRepository,
                                     DaHexAssignmentRepository assignmentRepository,
                                     GridProperties properties) {
        this.proposalRepository = proposalRepository;
        this.regionRepository = regionRepository;
        this.assignmentRepository = assignmentRepository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public AssignmentProposal computeProposal(UUID cityId, LocalDate validForDate,
                                              List<HexDemandSnapshot> demand,
                                              Map<UUID, List<UUID>> adjacencyGraph,
                                              List<UUID> availableDaIds) {
        if (demand.isEmpty() || availableDaIds.isEmpty()) {
            return persistProposal(cityId, validForDate, availableDaIds, demand,
                    List.of(), Map.of(), List.of(), 1.0, 1.0, 0,
                    availableDaIds.size(), adjacencyGraph);
        }

        int n = demand.size();
        int K = availableDaIds.size();

        List<UUID> hexIds = demand.stream().map(HexDemandSnapshot::getHexId).toList();
        Map<UUID, Integer> hexIndexMap = new HashMap<>();
        for (int i = 0; i < n; i++) hexIndexMap.put(hexIds.get(i), i);

        double[] demandArr = new double[n];
        for (int i = 0; i < n; i++) demandArr[i] = demand.get(i).getDemandScoreMinutes();

        List<List<Integer>> adj = buildIndexedAdjacency(n, hexIds, hexIndexMap, adjacencyGraph);

        int shiftMin = (properties.getShift().getEndHour() - properties.getShift().getStartHour()) * 60;
        double daCapacity = shiftMin * properties.getDa().getTargetUtilisation();
        double totalDemand = Arrays.stream(demandArr).sum();
        double target = totalDemand > 0 ? totalDemand / K : daCapacity;

        int[] seeds = computeSeeds(n, adj, demandArr, K);

        // Phase 1: competitive flooding — all DAs grow simultaneously from seeds.
        // The DA with the most remaining capacity always expands first, which drives load balance.
        int[] hexToDA = competitiveFlooding(n, K, adj, demandArr, seeds, target);

        // Phase 2: boundary swap refinement — iteratively move border hexes between adjacent
        // DAs when the move improves load balance and the donor territory stays connected.
        if (!adjacencyGraph.isEmpty()) {
            boundarySwapRefinement(n, K, adj, demandArr, seeds, hexToDA, target);
        }

        Map<Integer, List<Integer>> territories = new HashMap<>();
        for (int k = 0; k < K; k++) territories.put(k, new ArrayList<>());
        List<Integer> understaffedIndices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (hexToDA[i] >= 0) territories.get(hexToDA[i]).add(i);
            else understaffedIndices.add(i);
        }

        return persistProposal(cityId, validForDate, availableDaIds, demand, hexIds,
                territories, understaffedIndices, daCapacity, target, n, K, adjacencyGraph);
    }

    private List<List<Integer>> buildIndexedAdjacency(int n, List<UUID> hexIds,
                                                       Map<UUID, Integer> hexIndexMap,
                                                       Map<UUID, List<UUID>> adjacencyGraph) {
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Integer> nbrs = new ArrayList<>();
            for (UUID nbId : adjacencyGraph.getOrDefault(hexIds.get(i), List.of())) {
                Integer j = hexIndexMap.get(nbId);
                if (j != null) nbrs.add(j);
            }
            adj.add(nbrs);
        }
        return adj;
    }

    /**
     * Selects K seeds spread maximally across the hex graph.
     * Seed 0 = highest-demand hex. Each subsequent seed = hex with the greatest
     * minimum BFS distance to any existing seed.
     */
    private int[] computeSeeds(int n, List<List<Integer>> adj, double[] demand, int K) {
        int[] seeds = new int[K];
        boolean[] picked = new boolean[n];
        int[] minGraphDist = new int[n];
        Arrays.fill(minGraphDist, Integer.MAX_VALUE);

        int first = 0;
        for (int i = 1; i < n; i++) if (demand[i] > demand[first]) first = i;
        seeds[0] = first;
        picked[first] = true;
        bfsUpdateMinDist(first, minGraphDist, adj, n);

        for (int k = 1; k < K; k++) {
            int next = -1;
            int maxDist = -1;
            for (int i = 0; i < n; i++) {
                if (!picked[i] && minGraphDist[i] > maxDist) {
                    maxDist = minGraphDist[i];
                    next = i;
                }
            }
            if (next == -1) {
                for (int i = 0; i < n; i++) { if (!picked[i]) { next = i; break; } }
                if (next == -1) next = 0;
            }
            seeds[k] = next;
            picked[next] = true;
            bfsUpdateMinDist(next, minGraphDist, adj, n);
        }
        return seeds;
    }

    private void bfsUpdateMinDist(int source, int[] minDist, List<List<Integer>> adj, int n) {
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[source] = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (int v : adj.get(u)) {
                if (dist[v] == Integer.MAX_VALUE) {
                    dist[v] = dist[u] + 1;
                    queue.add(v);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (dist[i] < minDist[i]) minDist[i] = dist[i];
        }
    }

    /**
     * All K seeds expand simultaneously. At each step, the DA with the most remaining
     * capacity grabs its highest-demand adjacent unassigned hex.
     */
    private int[] competitiveFlooding(int n, int K, List<List<Integer>> adj,
                                       double[] demand, int[] seeds, double target) {
        int[] hexToDA = new int[n];
        Arrays.fill(hexToDA, -1);
        double[] remaining = new double[K];
        Arrays.fill(remaining, target);

        @SuppressWarnings("unchecked")
        Set<Integer>[] frontiers = new Set[K];
        for (int k = 0; k < K; k++) frontiers[k] = new HashSet<>();

        for (int k = 0; k < K; k++) {
            int s = seeds[k];
            hexToDA[s] = k;
            remaining[k] -= demand[s];
            for (int nb : adj.get(s)) {
                if (hexToDA[nb] == -1) frontiers[k].add(nb);
            }
        }

        int unassigned = 0;
        for (int i = 0; i < n; i++) if (hexToDA[i] == -1) unassigned++;

        while (unassigned > 0) {
            int bestDA = -1;
            double bestCap = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < K; k++) {
                if (!frontiers[k].isEmpty() && remaining[k] > bestCap) {
                    bestCap = remaining[k];
                    bestDA = k;
                }
            }

            if (bestDA == -1) {
                for (int i = 0; i < n; i++) {
                    if (hexToDA[i] == -1) {
                        int bestK = 0;
                        for (int k = 1; k < K; k++) if (remaining[k] > remaining[bestK]) bestK = k;
                        hexToDA[i] = bestK;
                        remaining[bestK] -= demand[i];
                        unassigned--;
                    }
                }
                break;
            }

            int bestHex = -1;
            double bestDemand = Double.NEGATIVE_INFINITY;
            Set<Integer> stale = new HashSet<>();
            for (int t : frontiers[bestDA]) {
                if (hexToDA[t] != -1) { stale.add(t); continue; }
                if (demand[t] > bestDemand) { bestDemand = demand[t]; bestHex = t; }
            }
            frontiers[bestDA].removeAll(stale);

            if (bestHex == -1) {
                frontiers[bestDA].clear();
                continue;
            }

            hexToDA[bestHex] = bestDA;
            remaining[bestDA] -= demand[bestHex];
            unassigned--;

            for (int nb : adj.get(bestHex)) {
                if (hexToDA[nb] == -1) frontiers[bestDA].add(nb);
            }
        }

        double[] loads = new double[K];
        for (int i = 0; i < n; i++) if (hexToDA[i] >= 0) loads[hexToDA[i]] += demand[i];
        double maxL = Arrays.stream(loads).max().orElse(0);
        double minL = Arrays.stream(loads).min().orElse(0);
        log.info("Phase 1 (competitive flooding): spread={} min  min={}  max={}  target={}",
                String.format("%.1f", maxL - minL), String.format("%.1f", minL),
                String.format("%.1f", maxL), String.format("%.1f", target));
        return hexToDA;
    }

    /**
     * For each border hex, evaluate moving it to an adjacent DA. Accept the move if it
     * strictly improves pairwise balance and the donor territory remains connected.
     */
    private void boundarySwapRefinement(int n, int K, List<List<Integer>> adj,
                                         double[] demand, int[] seeds,
                                         int[] hexToDA, double target) {
        double[] loads = new double[K];
        for (int i = 0; i < n; i++) if (hexToDA[i] >= 0) loads[hexToDA[i]] += demand[i];

        int totalSwaps = 0;
        for (int pass = 0; pass < MAX_SWAP_PASSES; pass++) {
            int bestHex = -1, bestFrom = -1, bestTo = -1;
            double bestImprovement = 1e-9;

            for (int t = 0; t < n; t++) {
                int from = hexToDA[t];
                if (from < 0) continue;

                Set<Integer> adjDAs = new HashSet<>();
                for (int nb : adj.get(t)) {
                    int j = hexToDA[nb];
                    if (j >= 0 && j != from) adjDAs.add(j);
                }
                if (adjDAs.isEmpty()) continue;

                double d = demand[t];
                double beforeFrom = Math.abs(loads[from] - target);

                for (int to : adjDAs) {
                    double before = beforeFrom + Math.abs(loads[to] - target);
                    double after = Math.abs(loads[from] - d - target)
                            + Math.abs(loads[to] + d - target);
                    double improvement = before - after;

                    if (improvement > bestImprovement
                            && donorRemainsConnected(t, from, n, adj, hexToDA, seeds)) {
                        bestImprovement = improvement;
                        bestHex = t;
                        bestFrom = from;
                        bestTo = to;
                    }
                }
            }

            if (bestHex == -1) break;

            hexToDA[bestHex] = bestTo;
            loads[bestFrom] -= demand[bestHex];
            loads[bestTo] += demand[bestHex];
            totalSwaps++;
        }

        double maxL = Arrays.stream(loads).max().orElse(0);
        double minL = Arrays.stream(loads).min().orElse(0);
        log.info("Phase 2 (boundary swaps): {} swaps, spread={} min  min={}  max={}",
                totalSwaps, String.format("%.1f", maxL - minL),
                String.format("%.1f", minL), String.format("%.1f", maxL));
    }

    private boolean donorRemainsConnected(int t, int from, int n,
                                           List<List<Integer>> adj, int[] hexToDA, int[] seeds) {
        int seed = seeds[from];
        if (seed == t) return false;

        int count = 0;
        for (int i = 0; i < n; i++) if (hexToDA[i] == from && i != t) count++;
        if (count == 0) return false;

        boolean[] visited = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(seed);
        visited[seed] = true;
        int reached = 1;

        while (!queue.isEmpty()) {
            int curr = queue.poll();
            for (int nb : adj.get(curr)) {
                if (!visited[nb] && hexToDA[nb] == from && nb != t) {
                    visited[nb] = true;
                    queue.add(nb);
                    reached++;
                }
            }
        }
        return reached == count;
    }

    private AssignmentProposal persistProposal(UUID cityId, LocalDate validForDate,
                                               List<UUID> availableDaIds,
                                               List<HexDemandSnapshot> demand,
                                               List<UUID> hexIds,
                                               Map<Integer, List<Integer>> territories,
                                               List<Integer> understaffedIndices,
                                               double daCapacity, double daTargetLoad,
                                               int nHexes, int K,
                                               Map<UUID, List<UUID>> adjacencyGraph) {
        Map<UUID, Boolean> bootstrappedMap = demand.stream()
                .collect(Collectors.toMap(HexDemandSnapshot::getHexId,
                        HexDemandSnapshot::isBootstrapped));

        List<UUID> understaffedHexIds = understaffedIndices.stream().map(hexIds::get).toList();

        AdjacencySource adjacencySource = adjacencyGraph.isEmpty()
                ? AdjacencySource.GEOMETRIC_FALLBACK : AdjacencySource.OSRM;

        AssignmentProposal proposal = proposalRepository.save(AssignmentProposal.builder()
                .cityId(cityId)
                .validForDate(validForDate)
                .status(ProposalStatus.PROPOSED)
                .solverType(SolverType.BALANCED_BFS)
                .adjacencySource(adjacencySource)
                .optimalityGapPct(null)
                .totalDas(K)
                .coveragePct(nHexes == 0 ? 100.0
                        : (double) (nHexes - understaffedHexIds.size()) / nHexes * 100.0)
                .understaffedHexIds(serializeUuids(understaffedHexIds))
                .build());

        List<AssignmentProposalRegion> regions = new ArrayList<>();
        List<DaHexAssignment> assignments = new ArrayList<>();

        for (int k = 0; k < K; k++) {
            UUID daId = availableDaIds.get(k);
            List<Integer> hexIndices = territories.getOrDefault(k, List.of());
            if (hexIndices.isEmpty()) continue;

            double totalDemand = hexIndices.stream()
                    .mapToDouble(i -> demand.get(i).getDemandScoreMinutes()).sum();
            boolean hasBootstrapped = hexIndices.stream()
                    .anyMatch(i -> bootstrappedMap.getOrDefault(hexIds.get(i), true));

            regions.add(AssignmentProposalRegion.builder()
                    .proposalId(proposal.getId())
                    .daId(daId)
                    .nDasRequired(1)
                    .estimatedDemandMin(totalDemand)
                    .estimatedUtilPct(totalDemand / daCapacity)
                    .hasBootstrappedTiles(hasBootstrapped)
                    .build());

            for (int idx : hexIndices) {
                assignments.add(DaHexAssignment.builder()
                        .proposalId(proposal.getId())
                        .daId(daId)
                        .hexId(hexIds.get(idx))
                        .validDate(validForDate)
                        .nDasOnHex(1)
                        .status(AssignmentStatus.PROPOSED)
                        .build());
            }
        }

        regionRepository.saveAll(regions);
        assignmentRepository.saveAll(assignments);

        log.info("BalancedBFS proposal {} created: {} DAs, {} hexes, {} understaffed",
                proposal.getId(), K, assignments.size(), understaffedHexIds.size());

        return proposal;
    }

    private String serializeUuids(List<UUID> uuids) {
        if (uuids.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(uuids.stream().map(UUID::toString).toList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
