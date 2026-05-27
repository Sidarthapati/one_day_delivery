package com.oneday.grid.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.Literal;
import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.AssignmentProposalRegion;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaTileAssignment;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.repository.AssignmentProposalRegionRepository;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaTileAssignmentRepository;
import com.oneday.grid.repository.TileRepository;
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
@Qualifier("cpSatAssignmentService")
class CpSatAssignmentServiceImpl implements AssignmentService {

    static {
        com.google.ortools.Loader.loadNativeLibraries();
    }

    private static final Logger log = LoggerFactory.getLogger(CpSatAssignmentServiceImpl.class);
    private static final int MAX_INFEASIBLE_RETRIES = 3;
    private static final double DEMAND_SCALE           = 100.0;
    private static final double INTER_TILE_TRAVEL_MIN  = 25.0;  // minutes overhead per tile in territory
    private static final long   DIST_PENALTY_SCALE     = 700L;  // 7 demand-minutes per grid-hop from seed

    private final AssignmentProposalRepository proposalRepository;
    private final AssignmentProposalRegionRepository regionRepository;
    private final DaTileAssignmentRepository assignmentRepository;
    private final TileRepository tileRepository;
    private final BfsAssignmentServiceImpl bfsFallback;
    private final GridProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CpSatAssignmentServiceImpl(AssignmentProposalRepository proposalRepository,
                               AssignmentProposalRegionRepository regionRepository,
                               DaTileAssignmentRepository assignmentRepository,
                               TileRepository tileRepository,
                               BfsAssignmentServiceImpl bfsFallback,
                               GridProperties properties) {
        this.proposalRepository = proposalRepository;
        this.regionRepository = regionRepository;
        this.assignmentRepository = assignmentRepository;
        this.tileRepository = tileRepository;
        this.bfsFallback = bfsFallback;
        this.properties = properties;
    }

    @Override
    @Transactional
    public AssignmentProposal computeProposal(UUID cityId, LocalDate validForDate,
                                              List<TileDemandSnapshot> demand,
                                              Map<UUID, List<UUID>> adjacencyGraph,
                                              List<UUID> availableDaIds) {
        if (demand.isEmpty() || availableDaIds.isEmpty()) {
            return bfsFallback.computeProposal(cityId, validForDate, demand, adjacencyGraph, availableDaIds);
        }

        int K = availableDaIds.size();

        // When the adjacency graph is non-empty, tiles with 0 active neighbours cannot be
        // seeds (no flow edges) and make the load-balance constraint INFEASIBLE for any
        // realistic DA count. Solve CP-SAT on connected tiles only; staple isolated tiles
        // to the nearest-seed DA post-solve.
        List<TileDemandSnapshot> connectedDemand;
        List<TileDemandSnapshot> isolatedDemand;
        if (adjacencyGraph.isEmpty()) {
            connectedDemand = demand;
            isolatedDemand = List.of();
        } else {
            connectedDemand = demand.stream()
                    .filter(d -> !adjacencyGraph.getOrDefault(d.getTileId(), List.of()).isEmpty())
                    .toList();
            isolatedDemand = demand.stream()
                    .filter(d -> adjacencyGraph.getOrDefault(d.getTileId(), List.of()).isEmpty())
                    .toList();
        }

        int nConnected = connectedDemand.size();
        if (nConnected == 0 || K > nConnected) {
            log.warn("CP-SAT: K={} DAs vs {} connected tiles — delegating to BFS", K, nConnected);
            return bfsFallback.computeProposal(cityId, validForDate, demand, adjacencyGraph, availableDaIds);
        }

        if (!isolatedDemand.isEmpty()) {
            log.info("CP-SAT: {} isolated tile(s) excluded from solver, will be stapled post-solve",
                    isolatedDemand.size());
        }

        List<UUID> tileIds = connectedDemand.stream().map(TileDemandSnapshot::getTileId).toList();
        Map<UUID, Integer> tileIndexMap = new HashMap<>();
        for (int i = 0; i < nConnected; i++) tileIndexMap.put(tileIds.get(i), i);

        // Load geometry for all tiles — needed for seed selection and isolated-tile stapling.
        List<UUID> allTileIds = demand.stream().map(TileDemandSnapshot::getTileId).toList();
        Map<UUID, Tile> tileMap = tileRepository.findAllById(allTileIds)
                .stream().collect(Collectors.toMap(Tile::getId, t -> t));

        int shiftMin = (properties.getShift().getEndHour() - properties.getShift().getStartHour()) * 60;
        double daCapacity = shiftMin * properties.getDa().getTargetUtilisation();

        long[] scaledDemand = new long[nConnected];
        for (int i = 0; i < nConnected; i++) {
            scaledDemand[i] = Math.round(connectedDemand.get(i).getDemandScoreMinutes() * DEMAND_SCALE);
        }

        // Each DA routes across its territory: (N-1) hops × λ min overhead, approximated as N×λ.
        // This makes large sprawling territories genuinely costlier, keeping the solver honest
        // about actual shift utilisation.
        long overheadPerTile = Math.round(INTER_TILE_TRAVEL_MIN * DEMAND_SCALE);
        long[] effectiveScaledDemand = new long[nConnected];
        for (int i = 0; i < nConnected; i++) effectiveScaledDemand[i] = scaledDemand[i] + overheadPerTile;

        double totalDemandMinutes = connectedDemand.stream()
                .mapToDouble(TileDemandSnapshot::getDemandScoreMinutes).sum();
        double effectiveTotalDemand = totalDemandMinutes + nConnected * INTER_TILE_TRAVEL_MIN;
        double daTargetLoad = effectiveTotalDemand > 0 ? effectiveTotalDemand / K : daCapacity;

        SeedResult sr = computeSeedIndices(connectedDemand, tileMap, K);
        double loadTolerance = properties.getSolver().getLoadTolerance();

        SolveResult result = null;
        for (int attempt = 0; attempt <= MAX_INFEASIBLE_RETRIES; attempt++) {
            if (attempt > 0) {
                loadTolerance += 0.05;
                log.warn("CP-SAT infeasible on attempt {}, widening load tolerance to {}", attempt, loadTolerance);
            }
            result = trySolve(tileIds, effectiveScaledDemand, K, daTargetLoad, loadTolerance,
                    adjacencyGraph, tileIndexMap, sr.seeds(), sr.tileRows(), sr.tileCols());
            if (result.status != SolveResult.Status.INFEASIBLE) break;
        }

        if (result == null || result.territories == null) {
            log.warn("CP-SAT failed for city={} date={}, delegating to BFS", cityId, validForDate);
            return bfsFallback.computeProposal(cityId, validForDate, demand, adjacencyGraph, availableDaIds);
        }

        // Phase 2: BFS connectivity repair.
        // For each DA, BFS from its seed through assigned tiles. Any tile not reached is
        // "disconnected" — reassign it to an adjacent DA whose neighbour tile IS reachable
        // from that DA's seed. This guarantees the move produces a connected territory.
        // Islands are absorbed layer-by-layer across iterations.
        if (!adjacencyGraph.isEmpty()) {
            long daCapacityScaled = Math.round(daCapacity * DEMAND_SCALE);
            Map<Integer, List<Integer>> repaired = repairConnectivity(
                    result.territories, tileIds, adjacencyGraph, tileIndexMap,
                    sr.seeds(), scaledDemand, daCapacityScaled, nConnected, K);
            result = new SolveResult(result.status, repaired, result.objectiveValue, result.bestBound);
        }

        // Staple each isolated tile to the DA whose seed is geographically nearest.
        if (!isolatedDemand.isEmpty()) {
            for (int j = 0; j < isolatedDemand.size(); j++) {
                Tile isoTile = tileMap.get(isolatedDemand.get(j).getTileId());
                int isoRow = isoTile != null ? isoTile.getRowIdx() : 0;
                int isoCol = isoTile != null ? isoTile.getColIdx() : 0;
                int nearestK = 0;
                double minDist = Double.MAX_VALUE;
                for (int k = 0; k < K; k++) {
                    int seedIdx = sr.seeds()[k];
                    double dr = isoRow - sr.tileRows()[seedIdx];
                    double dc = isoCol - sr.tileCols()[seedIdx];
                    double d = dr * dr + dc * dc;
                    if (d < minDist) { minDist = d; nearestK = k; }
                }
                result.territories.get(nearestK).add(nConnected + j);
            }
        }

        // Build combined lists: connected tiles first, isolated tiles appended.
        List<TileDemandSnapshot> combinedDemand = new ArrayList<>(connectedDemand);
        combinedDemand.addAll(isolatedDemand);
        List<UUID> combinedTileIds = new ArrayList<>(tileIds);
        isolatedDemand.forEach(d -> combinedTileIds.add(d.getTileId()));

        return persistProposal(cityId, validForDate, availableDaIds, combinedDemand, combinedTileIds,
                adjacencyGraph, result, daCapacity, combinedTileIds.size(), K);
    }

    /** Carries seed indices plus per-tile position arrays (reused for warm-start hints). */
    private record SeedResult(int[] seeds, int[] tileRows, int[] tileCols) {}

    private SolveResult trySolve(List<UUID> tileIds, long[] scaledDemand, int K,
                                  double daTargetLoad, double loadTolerance,
                                  Map<UUID, List<UUID>> adjacencyGraph,
                                  Map<UUID, Integer> tileIndexMap,
                                  int[] seedIndices, int[] tileRows, int[] tileCols) {
        int nTiles = tileIds.size();
        long scaledLb = Math.round(daTargetLoad * (1.0 - loadTolerance) * DEMAND_SCALE);
        long scaledUb = Math.round(daTargetLoad * (1.0 + loadTolerance) * DEMAND_SCALE);
        long maxPossibleLoad = Arrays.stream(scaledDemand).sum();

        CpModel model = new CpModel();

        // b[i][k] = true iff tile i is assigned to DA k
        BoolVar[][] b = new BoolVar[nTiles][K];
        for (int i = 0; i < nTiles; i++) {
            for (int k = 0; k < K; k++) {
                b[i][k] = model.newBoolVar("b_" + i + "_" + k);
            }
            Literal[] row = new Literal[K];
            for (int k = 0; k < K; k++) row[k] = b[i][k];
            model.addExactlyOne(row);
        }

        // Load variable per DA — weighted sum of demand for assigned tiles
        IntVar[] loads = new IntVar[K];
        for (int k = 0; k < K; k++) {
            loads[k] = model.newIntVar(0, maxPossibleLoad, "load_" + k);
            var expr = LinearExpr.newBuilder();
            for (int i = 0; i < nTiles; i++) expr.addTerm(b[i][k], scaledDemand[i]);
            model.addEquality(loads[k], expr);
            model.addLinearConstraint(loads[k], scaledLb, scaledUb);
        }

        // Seed positions — shared by objective penalty and warm-start hint
        int[] seedRows = new int[K];
        int[] seedCols = new int[K];
        for (int k = 0; k < K; k++) {
            seedRows[k] = tileRows[seedIndices[k]];
            seedCols[k] = tileCols[seedIndices[k]];
        }

        // Objective: minimise load spread + soft distance penalty (compactness proxy for connectivity).
        // β × dist(tile_i, seed_k) × b[i][k] makes assigning a far tile to DA k increasingly costly,
        // encouraging compact territories without any hard flow constraint.
        IntVar maxLoad = model.newIntVar(0, maxPossibleLoad, "maxLoad");
        IntVar minLoad = model.newIntVar(0, maxPossibleLoad, "minLoad");
        model.addMaxEquality(maxLoad, Arrays.asList(loads));
        model.addMinEquality(minLoad, Arrays.asList(loads));
        var objExpr = LinearExpr.newBuilder().add(maxLoad).addTerm(minLoad, -1L);
        for (int i = 0; i < nTiles; i++) {
            for (int k = 0; k < K; k++) {
                double dr = tileRows[i] - seedRows[k];
                double dc = tileCols[i] - seedCols[k];
                long penalty = Math.round(Math.sqrt(dr * dr + dc * dc) * DIST_PENALTY_SCALE);
                if (penalty > 0) objExpr.addTerm(b[i][k], penalty);
            }
        }
        model.minimize(objExpr);

        // Symmetry breaking: seed tile k must be assigned to DA k
        for (int k = 0; k < K; k++) {
            model.addBoolAnd(new Literal[]{b[seedIndices[k]][k]});
        }

        log.info("CP-SAT: load-balance + distance-penalty ({} tiles, {} DAs, avg {} tiles/DA)",
                nTiles, K, nTiles / K);

        // Warm-start: assign each tile to its nearest seed (Voronoi)
        for (int i = 0; i < nTiles; i++) {
            int nearestK = 0;
            double minDist = Double.MAX_VALUE;
            for (int k = 0; k < K; k++) {
                double dr = tileRows[i] - seedRows[k];
                double dc = tileCols[i] - seedCols[k];
                double d = dr * dr + dc * dc;
                if (d < minDist) { minDist = d; nearestK = k; }
            }
            for (int k = 0; k < K; k++) {
                model.addHint(b[i][k], k == nearestK ? 1 : 0);
            }
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(properties.getSolver().getTimeLimitSeconds());
        CpSolverStatus status = solver.solve(model);

        if (status == CpSolverStatus.INFEASIBLE || status == CpSolverStatus.MODEL_INVALID) {
            return SolveResult.infeasible();
        }
        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            return SolveResult.timeout();
        }

        Map<Integer, List<Integer>> territories = extractTerritories(solver, b, nTiles, K);
        return new SolveResult(SolveResult.Status.SOLVED, territories,
                solver.objectiveValue(), solver.bestObjectiveBound());
    }

    /**
     * Phase 2 of two-phase solve: BFS connectivity repair.
     *
     * CP-SAT Phase 1 (load balance + distance penalty) occasionally produces disconnected
     * territories. This method repairs them by BFS-ing from each seed and reassigning
     * unreachable tiles to adjacent DAs whose entry tile IS reachable from their seed.
     * Islands are absorbed layer-by-layer across iterations.
     */
    private Map<Integer, List<Integer>> repairConnectivity(
            Map<Integer, List<Integer>> territories,
            List<UUID> tileIds,
            Map<UUID, List<UUID>> adjacencyGraph,
            Map<UUID, Integer> tileIndexMap,
            int[] seedIndices,
            long[] scaledDemand,
            long daCapacityScaled,
            int nTiles, int K) {

        List<List<Integer>> adj = new ArrayList<>(nTiles);
        for (int i = 0; i < nTiles; i++) {
            List<Integer> nbrs = new ArrayList<>();
            for (UUID nbId : adjacencyGraph.getOrDefault(tileIds.get(i), List.of())) {
                Integer j = tileIndexMap.get(nbId);
                if (j != null) nbrs.add(j);
            }
            adj.add(nbrs);
        }

        int[] tileToDA = new int[nTiles];
        Arrays.fill(tileToDA, -1);
        List<Set<Integer>> terSets = new ArrayList<>(K);
        for (int k = 0; k < K; k++) {
            terSets.add(new HashSet<>(territories.get(k)));
            for (int t : territories.get(k)) tileToDA[t] = k;
        }

        long[] remainingCap = new long[K];
        for (int k = 0; k < K; k++) {
            long load = 0;
            for (int t : terSets.get(k)) load += scaledDemand[t];
            remainingCap[k] = daCapacityScaled - load;
        }

        int totalReassigned = 0;
        for (int iter = 0; iter < nTiles; iter++) {
            boolean[][] reachable = new boolean[K][nTiles];
            for (int k = 0; k < K; k++) {
                int seed = seedIndices[k];
                if (!terSets.get(k).contains(seed)) continue;
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(seed);
                reachable[k][seed] = true;
                while (!queue.isEmpty()) {
                    int t = queue.poll();
                    for (int nb : adj.get(t)) {
                        if (!reachable[k][nb] && terSets.get(k).contains(nb)) {
                            reachable[k][nb] = true;
                            queue.add(nb);
                        }
                    }
                }
            }

            List<int[]> disconnected = new ArrayList<>();
            for (int k = 0; k < K; k++) {
                for (int t : terSets.get(k)) {
                    if (!reachable[k][t]) disconnected.add(new int[]{t, k});
                }
            }
            if (disconnected.isEmpty()) break;

            int reassignedThisRound = 0;
            for (int[] pair : disconnected) {
                int t = pair[0];
                int fromDA = pair[1];
                // Only absorb into DA j if the entry neighbour is reachable from j's seed.
                // Pick by remaining capacity to keep load as balanced as possible.
                int bestDA = -1;
                long bestCap = Long.MIN_VALUE;
                for (int nb : adj.get(t)) {
                    int j = tileToDA[nb];
                    if (j != fromDA && j >= 0 && reachable[j][nb] && remainingCap[j] > bestCap) {
                        bestCap = remainingCap[j];
                        bestDA = j;
                    }
                }
                if (bestDA >= 0) {
                    terSets.get(fromDA).remove(t);
                    terSets.get(bestDA).add(t);
                    remainingCap[fromDA] += scaledDemand[t];
                    remainingCap[bestDA] -= scaledDemand[t];
                    tileToDA[t] = bestDA;
                    reassignedThisRound++;
                    totalReassigned++;
                }
            }

            if (reassignedThisRound == 0) {
                log.warn("CP-SAT repair: {} tiles remain disconnected after exhausting adjacency", disconnected.size());
                break;
            }
        }

        log.info("CP-SAT Phase 2 repair: {} tiles reassigned for connectivity", totalReassigned);
        Map<Integer, List<Integer>> result = new HashMap<>(K);
        for (int k = 0; k < K; k++) result.put(k, new ArrayList<>(terSets.get(k)));
        return result;
    }

    /**
     * Furthest-first geographic seed selection.
     * Picks K tile indices maximally spread across the grid so each DA starts from a
     * different geographic area. Starts from the tile nearest the bounding-box center
     * for a deterministic result.
     */
    private SeedResult computeSeedIndices(List<TileDemandSnapshot> demand, Map<UUID, Tile> tileMap, int K) {
        int n = demand.size();
        int[] row = new int[n];
        int[] col = new int[n];
        for (int i = 0; i < n; i++) {
            Tile t = tileMap.get(demand.get(i).getTileId());
            row[i] = t != null ? t.getRowIdx() : 0;
            col[i] = t != null ? t.getColIdx() : 0;
        }

        double meanRow = 0, meanCol = 0;
        for (int i = 0; i < n; i++) { meanRow += row[i]; meanCol += col[i]; }
        meanRow /= n; meanCol /= n;
        int first = 0;
        double closestDist = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double dr = row[i] - meanRow, dc = col[i] - meanCol;
            double d = dr * dr + dc * dc;
            if (d < closestDist) { closestDist = d; first = i; }
        }

        int[] seeds = new int[K];
        boolean[] picked = new boolean[n];
        double[] minDist = new double[n];
        Arrays.fill(minDist, Double.MAX_VALUE);

        seeds[0] = first;
        picked[first] = true;
        for (int i = 0; i < n; i++) {
            double dr = row[i] - row[first], dc = col[i] - col[first];
            minDist[i] = dr * dr + dc * dc;
        }

        for (int k = 1; k < K; k++) {
            int next = -1;
            double maxD = -1;
            for (int i = 0; i < n; i++) {
                if (!picked[i] && minDist[i] > maxD) { maxD = minDist[i]; next = i; }
            }
            if (next == -1) next = 0;
            seeds[k] = next;
            picked[next] = true;
            for (int i = 0; i < n; i++) {
                double dr = row[i] - row[next], dc = col[i] - col[next];
                double d = dr * dr + dc * dc;
                if (d < minDist[i]) minDist[i] = d;
            }
        }
        log.info("CP-SAT geographic seeds (row,col): {}",
                Arrays.stream(seeds).mapToObj(i -> "(" + row[i] + "," + col[i] + ")").toList());
        return new SeedResult(seeds, row, col);
    }

    private Map<Integer, List<Integer>> extractTerritories(CpSolver solver, BoolVar[][] b, int nTiles, int K) {
        Map<Integer, List<Integer>> territories = new HashMap<>();
        for (int k = 0; k < K; k++) territories.put(k, new ArrayList<>());
        for (int i = 0; i < nTiles; i++) {
            for (int k = 0; k < K; k++) {
                if (solver.booleanValue(b[i][k])) {
                    territories.get(k).add(i);
                    break;
                }
            }
        }
        return territories;
    }

    private AssignmentProposal persistProposal(UUID cityId, LocalDate validForDate,
                                               List<UUID> availableDaIds,
                                               List<TileDemandSnapshot> demand,
                                               List<UUID> tileIds,
                                               Map<UUID, List<UUID>> adjacencyGraph,
                                               SolveResult result,
                                               double daCapacity,
                                               int nTiles, int K) {
        Map<UUID, Boolean> bootstrappedMap = demand.stream()
                .collect(Collectors.toMap(TileDemandSnapshot::getTileId, TileDemandSnapshot::isBootstrapped));

        boolean[] assigned = new boolean[nTiles];
        for (List<Integer> territory : result.territories.values()) territory.forEach(i -> assigned[i] = true);
        List<UUID> understaffedTileIds = new ArrayList<>();
        for (int i = 0; i < nTiles; i++) if (!assigned[i]) understaffedTileIds.add(tileIds.get(i));

        double optGap = (result.objectiveValue - result.bestBound)
                / Math.max(result.objectiveValue, 1e-6) * 100.0;
        AdjacencySource adjacencySource = adjacencyGraph.isEmpty()
                ? AdjacencySource.GEOMETRIC_FALLBACK : AdjacencySource.OSRM;

        AssignmentProposal proposal = proposalRepository.save(AssignmentProposal.builder()
                .cityId(cityId)
                .validForDate(validForDate)
                .status(ProposalStatus.PROPOSED)
                .solverType(SolverType.CP_SAT)
                .adjacencySource(adjacencySource)
                .optimalityGapPct(optGap)
                .totalDas(K)
                .coveragePct(nTiles == 0 ? 100.0
                        : (double) (nTiles - understaffedTileIds.size()) / nTiles * 100.0)
                .understaffedTileIds(serializeUuids(understaffedTileIds))
                .build());

        List<AssignmentProposalRegion> regions = new ArrayList<>();
        List<DaTileAssignment> assignments = new ArrayList<>();

        for (int k = 0; k < K; k++) {
            UUID daId = availableDaIds.get(k);
            List<Integer> tileIndices = result.territories.getOrDefault(k, List.of());
            if (tileIndices.isEmpty()) continue;

            double totalDemand = tileIndices.stream()
                    .mapToDouble(i -> demand.get(i).getDemandScoreMinutes()).sum()
                    + tileIndices.size() * INTER_TILE_TRAVEL_MIN;
            boolean hasBootstrapped = tileIndices.stream()
                    .anyMatch(i -> bootstrappedMap.getOrDefault(tileIds.get(i), true));

            regions.add(AssignmentProposalRegion.builder()
                    .proposalId(proposal.getId())
                    .daId(daId)
                    .nDasRequired(1)
                    .estimatedDemandMin(totalDemand)
                    .estimatedUtilPct(totalDemand / daCapacity)
                    .hasBootstrappedTiles(hasBootstrapped)
                    .build());

            for (int idx : tileIndices) {
                assignments.add(DaTileAssignment.builder()
                        .proposalId(proposal.getId())
                        .daId(daId)
                        .tileId(tileIds.get(idx))
                        .validDate(validForDate)
                        .nDasOnTile(1)
                        .status(AssignmentStatus.PROPOSED)
                        .build());
            }
        }

        regionRepository.saveAll(regions);
        assignmentRepository.saveAll(assignments);

        log.info("CP-SAT proposal {} created: {} DAs, {} tiles, gap={}%",
                proposal.getId(), K, assignments.size(), optGap);

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

    private record SolveResult(Status status, Map<Integer, List<Integer>> territories,
                                double objectiveValue, double bestBound) {
        enum Status { SOLVED, INFEASIBLE, TIMEOUT }

        static SolveResult infeasible() { return new SolveResult(Status.INFEASIBLE, null, 0, 0); }
        static SolveResult timeout()    { return new SolveResult(Status.TIMEOUT,    null, 0, 0); }
    }
}
