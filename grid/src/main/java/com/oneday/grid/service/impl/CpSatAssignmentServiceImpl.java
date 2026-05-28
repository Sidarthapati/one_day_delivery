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
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.domain.HexDemandSnapshot;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.repository.AssignmentProposalRegionRepository;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.service.AssignmentService;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
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
    private static final int    MAX_INFEASIBLE_RETRIES = 3;
    private static final double DEMAND_SCALE           = 100.0;
    private static final double INTER_HEX_TRAVEL_MIN   = 25.0;   // minutes overhead per hex in territory
    private static final long   DIST_PENALTY_SCALE     = 700L;   // 7 demand-minutes per hex-step from seed
    // H3 resolution-7 center-to-center ≈ 2.1 km ≈ 0.019° — normalises lat/lon to hex-step units
    private static final double H3_STEP_DEG            = 0.019;

    private final AssignmentProposalRepository proposalRepository;
    private final AssignmentProposalRegionRepository regionRepository;
    private final DaHexAssignmentRepository assignmentRepository;
    private final HexRepository hexRepository;
    private final BfsAssignmentServiceImpl bfsFallback;
    private final GridProperties properties;
    private final H3Core h3Core;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CpSatAssignmentServiceImpl(AssignmentProposalRepository proposalRepository,
                               AssignmentProposalRegionRepository regionRepository,
                               DaHexAssignmentRepository assignmentRepository,
                               HexRepository hexRepository,
                               BfsAssignmentServiceImpl bfsFallback,
                               GridProperties properties,
                               H3Core h3Core) {
        this.proposalRepository = proposalRepository;
        this.regionRepository = regionRepository;
        this.assignmentRepository = assignmentRepository;
        this.hexRepository = hexRepository;
        this.bfsFallback = bfsFallback;
        this.properties = properties;
        this.h3Core = h3Core;
    }

    @Override
    @Transactional
    public AssignmentProposal computeProposal(UUID cityId, LocalDate validForDate,
                                              List<HexDemandSnapshot> demand,
                                              Map<UUID, List<UUID>> adjacencyGraph,
                                              List<UUID> availableDaIds) {
        if (demand.isEmpty() || availableDaIds.isEmpty()) {
            return bfsFallback.computeProposal(cityId, validForDate, demand, adjacencyGraph, availableDaIds);
        }

        int K = availableDaIds.size();

        // When the adjacency graph is non-empty, hexes with 0 active neighbours cannot be
        // seeds (no flow edges) and make the load-balance constraint INFEASIBLE for any
        // realistic DA count. Solve CP-SAT on connected hexes only; staple isolated hexes
        // to the nearest-seed DA post-solve.
        List<HexDemandSnapshot> connectedDemand;
        List<HexDemandSnapshot> isolatedDemand;
        if (adjacencyGraph.isEmpty()) {
            connectedDemand = demand;
            isolatedDemand = List.of();
        } else {
            connectedDemand = demand.stream()
                    .filter(d -> !adjacencyGraph.getOrDefault(d.getHexId(), List.of()).isEmpty())
                    .toList();
            isolatedDemand = demand.stream()
                    .filter(d -> adjacencyGraph.getOrDefault(d.getHexId(), List.of()).isEmpty())
                    .toList();
        }

        int nConnected = connectedDemand.size();
        if (nConnected == 0 || K > nConnected) {
            log.warn("CP-SAT: K={} DAs vs {} connected hexes — delegating to BFS", K, nConnected);
            return bfsFallback.computeProposal(cityId, validForDate, demand, adjacencyGraph, availableDaIds);
        }

        if (!isolatedDemand.isEmpty()) {
            log.info("CP-SAT: {} isolated hex(es) excluded from solver, will be stapled post-solve",
                    isolatedDemand.size());
        }

        List<UUID> hexIds = connectedDemand.stream().map(HexDemandSnapshot::getHexId).toList();
        Map<UUID, Integer> hexIndexMap = new HashMap<>();
        for (int i = 0; i < nConnected; i++) hexIndexMap.put(hexIds.get(i), i);

        // Load geometry for all hexes — needed for seed selection and isolated-hex stapling.
        List<UUID> allHexIds = demand.stream().map(HexDemandSnapshot::getHexId).toList();
        Map<UUID, Hex> hexMap = hexRepository.findAllById(allHexIds)
                .stream().collect(Collectors.toMap(Hex::getId, h -> h));

        int shiftMin = (properties.getShift().getEndHour() - properties.getShift().getStartHour()) * 60;
        double daCapacity = shiftMin * properties.getDa().getTargetUtilisation();

        long[] scaledDemand = new long[nConnected];
        for (int i = 0; i < nConnected; i++) {
            scaledDemand[i] = Math.round(connectedDemand.get(i).getDemandScoreMinutes() * DEMAND_SCALE);
        }

        // Each DA routes across its territory: (N-1) hops × λ min overhead, approximated as N×λ.
        long overheadPerHex = Math.round(INTER_HEX_TRAVEL_MIN * DEMAND_SCALE);
        long[] effectiveScaledDemand = new long[nConnected];
        for (int i = 0; i < nConnected; i++) effectiveScaledDemand[i] = scaledDemand[i] + overheadPerHex;

        double totalDemandMinutes = connectedDemand.stream()
                .mapToDouble(HexDemandSnapshot::getDemandScoreMinutes).sum();
        double effectiveTotalDemand = totalDemandMinutes + nConnected * INTER_HEX_TRAVEL_MIN;
        double daTargetLoad = effectiveTotalDemand > 0 ? effectiveTotalDemand / K : daCapacity;

        SeedResult sr = computeSeedIndices(connectedDemand, hexMap, K);
        double loadTolerance = properties.getSolver().getLoadTolerance();

        SolveResult result = null;
        for (int attempt = 0; attempt <= MAX_INFEASIBLE_RETRIES; attempt++) {
            if (attempt > 0) {
                loadTolerance += 0.05;
                log.warn("CP-SAT infeasible on attempt {}, widening load tolerance to {}", attempt, loadTolerance);
            }
            result = trySolve(hexIds, effectiveScaledDemand, K, daTargetLoad, loadTolerance,
                    adjacencyGraph, hexIndexMap, sr.seeds(), sr.hexLats(), sr.hexLons());
            if (result.status != SolveResult.Status.INFEASIBLE) break;
        }

        if (result == null || result.territories == null) {
            log.warn("CP-SAT failed for city={} date={}, delegating to BFS", cityId, validForDate);
            return bfsFallback.computeProposal(cityId, validForDate, demand, adjacencyGraph, availableDaIds);
        }

        // Phase 2: BFS connectivity repair.
        // For each DA, BFS from its seed through assigned hexes. Any hex not reached is
        // "disconnected" — reassign it to an adjacent DA whose entry hex IS reachable
        // from that DA's seed.
        if (!adjacencyGraph.isEmpty()) {
            long daCapacityScaled = Math.round(daCapacity * DEMAND_SCALE);
            Map<Integer, List<Integer>> repaired = repairConnectivity(
                    result.territories, hexIds, adjacencyGraph, hexIndexMap,
                    sr.seeds(), scaledDemand, daCapacityScaled, nConnected, K);
            result = new SolveResult(result.status, repaired, result.objectiveValue, result.bestBound);
        }

        // Staple each isolated hex to the DA whose seed is geographically nearest.
        if (!isolatedDemand.isEmpty()) {
            for (int j = 0; j < isolatedDemand.size(); j++) {
                Hex isoHex = hexMap.get(isolatedDemand.get(j).getHexId());
                double isoLat = 0, isoLon = 0;
                if (isoHex != null) {
                    LatLng c = h3Core.cellToLatLng(isoHex.getH3Index());
                    isoLat = c.lat;
                    isoLon = c.lng;
                }
                int nearestK = 0;
                double minDist = Double.MAX_VALUE;
                for (int k = 0; k < K; k++) {
                    int seedIdx = sr.seeds()[k];
                    double dr = isoLat - sr.hexLats()[seedIdx];
                    double dc = isoLon - sr.hexLons()[seedIdx];
                    double d = dr * dr + dc * dc;
                    if (d < minDist) { minDist = d; nearestK = k; }
                }
                result.territories.get(nearestK).add(nConnected + j);
            }
        }

        // Build combined lists: connected hexes first, isolated hexes appended.
        List<HexDemandSnapshot> combinedDemand = new ArrayList<>(connectedDemand);
        combinedDemand.addAll(isolatedDemand);
        List<UUID> combinedHexIds = new ArrayList<>(hexIds);
        isolatedDemand.forEach(d -> combinedHexIds.add(d.getHexId()));

        return persistProposal(cityId, validForDate, availableDaIds, combinedDemand, combinedHexIds,
                adjacencyGraph, result, daCapacity, combinedHexIds.size(), K);
    }

    /** Carries seed indices plus per-hex centroid arrays (reused for warm-start hints). */
    private record SeedResult(int[] seeds, double[] hexLats, double[] hexLons) {}

    private SolveResult trySolve(List<UUID> hexIds, long[] scaledDemand, int K,
                                  double daTargetLoad, double loadTolerance,
                                  Map<UUID, List<UUID>> adjacencyGraph,
                                  Map<UUID, Integer> hexIndexMap,
                                  int[] seedIndices, double[] hexLats, double[] hexLons) {
        int nHexes = hexIds.size();
        long scaledLb = Math.round(daTargetLoad * (1.0 - loadTolerance) * DEMAND_SCALE);
        long scaledUb = Math.round(daTargetLoad * (1.0 + loadTolerance) * DEMAND_SCALE);
        long maxPossibleLoad = Arrays.stream(scaledDemand).sum();

        CpModel model = new CpModel();

        // b[i][k] = true iff hex i is assigned to DA k
        BoolVar[][] b = new BoolVar[nHexes][K];
        for (int i = 0; i < nHexes; i++) {
            for (int k = 0; k < K; k++) {
                b[i][k] = model.newBoolVar("b_" + i + "_" + k);
            }
            Literal[] row = new Literal[K];
            for (int k = 0; k < K; k++) row[k] = b[i][k];
            model.addExactlyOne(row);
        }

        // Load variable per DA — weighted sum of demand for assigned hexes
        IntVar[] loads = new IntVar[K];
        for (int k = 0; k < K; k++) {
            loads[k] = model.newIntVar(0, maxPossibleLoad, "load_" + k);
            var expr = LinearExpr.newBuilder();
            for (int i = 0; i < nHexes; i++) expr.addTerm(b[i][k], scaledDemand[i]);
            model.addEquality(loads[k], expr);
            model.addLinearConstraint(loads[k], scaledLb, scaledUb);
        }

        // Seed positions — shared by objective penalty and warm-start hint
        double[] seedLats = new double[K];
        double[] seedLons = new double[K];
        for (int k = 0; k < K; k++) {
            seedLats[k] = hexLats[seedIndices[k]];
            seedLons[k] = hexLons[seedIndices[k]];
        }

        // Objective: minimise load spread + soft distance penalty (compactness proxy for connectivity).
        // β × dist(hex_i, seed_k) × b[i][k] makes assigning a far hex to DA k increasingly costly,
        // encouraging compact territories. Distance is normalised to hex-step units so
        // DIST_PENALTY_SCALE keeps its original meaning (7 demand-minutes per hex-step).
        IntVar maxLoad = model.newIntVar(0, maxPossibleLoad, "maxLoad");
        IntVar minLoad = model.newIntVar(0, maxPossibleLoad, "minLoad");
        model.addMaxEquality(maxLoad, Arrays.asList(loads));
        model.addMinEquality(minLoad, Arrays.asList(loads));
        var objExpr = LinearExpr.newBuilder().add(maxLoad).addTerm(minLoad, -1L);
        for (int i = 0; i < nHexes; i++) {
            for (int k = 0; k < K; k++) {
                double dr = (hexLats[i] - seedLats[k]) / H3_STEP_DEG;
                double dc = (hexLons[i] - seedLons[k]) / H3_STEP_DEG;
                long penalty = Math.round(Math.sqrt(dr * dr + dc * dc) * DIST_PENALTY_SCALE);
                if (penalty > 0) objExpr.addTerm(b[i][k], penalty);
            }
        }
        model.minimize(objExpr);

        // Symmetry breaking: seed hex k must be assigned to DA k
        for (int k = 0; k < K; k++) {
            model.addBoolAnd(new Literal[]{b[seedIndices[k]][k]});
        }

        log.info("CP-SAT: load-balance + distance-penalty ({} hexes, {} DAs, avg {} hexes/DA)",
                nHexes, K, nHexes / K);

        // Warm-start: assign each hex to its nearest seed (Voronoi)
        for (int i = 0; i < nHexes; i++) {
            int nearestK = 0;
            double minDist = Double.MAX_VALUE;
            for (int k = 0; k < K; k++) {
                double dr = hexLats[i] - seedLats[k];
                double dc = hexLons[i] - seedLons[k];
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

        Map<Integer, List<Integer>> territories = extractTerritories(solver, b, nHexes, K);
        return new SolveResult(SolveResult.Status.SOLVED, territories,
                solver.objectiveValue(), solver.bestObjectiveBound());
    }

    /**
     * Phase 2 of two-phase solve: BFS connectivity repair.
     *
     * CP-SAT Phase 1 (load balance + distance penalty) occasionally produces disconnected
     * territories. This method repairs them by BFS-ing from each seed and reassigning
     * unreachable hexes to adjacent DAs whose entry hex IS reachable from their seed.
     * Islands are absorbed layer-by-layer across iterations.
     */
    private Map<Integer, List<Integer>> repairConnectivity(
            Map<Integer, List<Integer>> territories,
            List<UUID> hexIds,
            Map<UUID, List<UUID>> adjacencyGraph,
            Map<UUID, Integer> hexIndexMap,
            int[] seedIndices,
            long[] scaledDemand,
            long daCapacityScaled,
            int nHexes, int K) {

        List<List<Integer>> adj = new ArrayList<>(nHexes);
        for (int i = 0; i < nHexes; i++) {
            List<Integer> nbrs = new ArrayList<>();
            for (UUID nbId : adjacencyGraph.getOrDefault(hexIds.get(i), List.of())) {
                Integer j = hexIndexMap.get(nbId);
                if (j != null) nbrs.add(j);
            }
            adj.add(nbrs);
        }

        int[] hexToDA = new int[nHexes];
        Arrays.fill(hexToDA, -1);
        List<Set<Integer>> terSets = new ArrayList<>(K);
        for (int k = 0; k < K; k++) {
            terSets.add(new HashSet<>(territories.get(k)));
            for (int t : territories.get(k)) hexToDA[t] = k;
        }

        long[] remainingCap = new long[K];
        for (int k = 0; k < K; k++) {
            long load = 0;
            for (int t : terSets.get(k)) load += scaledDemand[t];
            remainingCap[k] = daCapacityScaled - load;
        }

        int totalReassigned = 0;
        for (int iter = 0; iter < nHexes; iter++) {
            boolean[][] reachable = new boolean[K][nHexes];
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
                    int j = hexToDA[nb];
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
                    hexToDA[t] = bestDA;
                    reassignedThisRound++;
                    totalReassigned++;
                }
            }

            if (reassignedThisRound == 0) {
                log.warn("CP-SAT repair: {} hexes remain disconnected after exhausting adjacency", disconnected.size());
                break;
            }
        }

        log.info("CP-SAT Phase 2 repair: {} hexes reassigned for connectivity", totalReassigned);
        Map<Integer, List<Integer>> result = new HashMap<>(K);
        for (int k = 0; k < K; k++) result.put(k, new ArrayList<>(terSets.get(k)));
        return result;
    }

    /**
     * Furthest-first geographic seed selection.
     * Picks K hex indices maximally spread across the grid so each DA starts from a
     * different geographic area. Starts from the hex nearest the bounding-box center
     * for a deterministic result.
     */
    private SeedResult computeSeedIndices(List<HexDemandSnapshot> demand, Map<UUID, Hex> hexMap, int K) {
        int n = demand.size();
        double[] lat = new double[n];
        double[] lon = new double[n];
        for (int i = 0; i < n; i++) {
            Hex h = hexMap.get(demand.get(i).getHexId());
            if (h != null) {
                LatLng c = h3Core.cellToLatLng(h.getH3Index());
                lat[i] = c.lat;
                lon[i] = c.lng;
            }
        }

        double meanLat = 0, meanLon = 0;
        for (int i = 0; i < n; i++) { meanLat += lat[i]; meanLon += lon[i]; }
        meanLat /= n; meanLon /= n;
        int first = 0;
        double closestDist = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double dr = lat[i] - meanLat, dc = lon[i] - meanLon;
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
            double dr = lat[i] - lat[first], dc = lon[i] - lon[first];
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
                double dr = lat[i] - lat[next], dc = lon[i] - lon[next];
                double d = dr * dr + dc * dc;
                if (d < minDist[i]) minDist[i] = d;
            }
        }
        log.info("CP-SAT geographic seeds (lat,lon): {}",
                Arrays.stream(seeds).mapToObj(i -> "(" + lat[i] + "," + lon[i] + ")").toList());
        return new SeedResult(seeds, lat, lon);
    }

    private Map<Integer, List<Integer>> extractTerritories(CpSolver solver, BoolVar[][] b, int nHexes, int K) {
        Map<Integer, List<Integer>> territories = new HashMap<>();
        for (int k = 0; k < K; k++) territories.put(k, new ArrayList<>());
        for (int i = 0; i < nHexes; i++) {
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
                                               List<HexDemandSnapshot> demand,
                                               List<UUID> hexIds,
                                               Map<UUID, List<UUID>> adjacencyGraph,
                                               SolveResult result,
                                               double daCapacity,
                                               int nHexes, int K) {
        Map<UUID, Boolean> bootstrappedMap = demand.stream()
                .collect(Collectors.toMap(HexDemandSnapshot::getHexId, HexDemandSnapshot::isBootstrapped));

        boolean[] assigned = new boolean[nHexes];
        for (List<Integer> territory : result.territories.values()) territory.forEach(i -> assigned[i] = true);
        List<UUID> understaffedHexIds = new ArrayList<>();
        for (int i = 0; i < nHexes; i++) if (!assigned[i]) understaffedHexIds.add(hexIds.get(i));

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
                .coveragePct(nHexes == 0 ? 100.0
                        : (double) (nHexes - understaffedHexIds.size()) / nHexes * 100.0)
                .understaffedHexIds(serializeUuids(understaffedHexIds))
                .build());

        List<AssignmentProposalRegion> regions = new ArrayList<>();
        List<DaHexAssignment> assignments = new ArrayList<>();

        for (int k = 0; k < K; k++) {
            UUID daId = availableDaIds.get(k);
            List<Integer> hexIndices = result.territories.getOrDefault(k, List.of());
            if (hexIndices.isEmpty()) continue;

            double totalDemand = hexIndices.stream()
                    .mapToDouble(i -> demand.get(i).getDemandScoreMinutes()).sum()
                    + hexIndices.size() * INTER_HEX_TRAVEL_MIN;
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

        log.info("CP-SAT proposal {} created: {} DAs, {} hexes, gap={}%",
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
