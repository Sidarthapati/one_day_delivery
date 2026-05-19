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
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.repository.AssignmentProposalRegionRepository;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaTileAssignmentRepository;
import com.oneday.grid.service.AssignmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final int MAX_LAZY_CUT_ROUNDS = 10;
    private static final double DEMAND_SCALE = 100.0;

    private final AssignmentProposalRepository proposalRepository;
    private final AssignmentProposalRegionRepository regionRepository;
    private final DaTileAssignmentRepository assignmentRepository;
    private final BfsAssignmentServiceImpl bfsFallback;
    private final GridProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CpSatAssignmentServiceImpl(AssignmentProposalRepository proposalRepository,
                               AssignmentProposalRegionRepository regionRepository,
                               DaTileAssignmentRepository assignmentRepository,
                               BfsAssignmentServiceImpl bfsFallback,
                               GridProperties properties) {
        this.proposalRepository = proposalRepository;
        this.regionRepository = regionRepository;
        this.assignmentRepository = assignmentRepository;
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

        int nTiles = demand.size();
        int K = availableDaIds.size();

        if (K > nTiles) {
            log.warn("CP-SAT: more DAs ({}) than tiles ({}) — delegating to BFS", K, nTiles);
            return bfsFallback.computeProposal(cityId, validForDate, demand, adjacencyGraph, availableDaIds);
        }

        List<UUID> tileIds = demand.stream().map(TileDemandSnapshot::getTileId).toList();
        Map<UUID, Integer> tileIndexMap = new HashMap<>();
        for (int i = 0; i < nTiles; i++) tileIndexMap.put(tileIds.get(i), i);

        int shiftMin = (properties.getShift().getEndHour() - properties.getShift().getStartHour()) * 60;
        double daTargetLoad = shiftMin * properties.getDa().getTargetUtilisation();

        long[] scaledDemand = new long[nTiles];
        for (int i = 0; i < nTiles; i++) {
            scaledDemand[i] = Math.round(demand.get(i).getDemandScoreMinutes() * DEMAND_SCALE);
        }

        int[] seedIndices = computeSeedIndices(scaledDemand, K);
        double loadTolerance = properties.getSolver().getLoadTolerance();

        SolveResult result = null;
        for (int attempt = 0; attempt <= MAX_INFEASIBLE_RETRIES; attempt++) {
            if (attempt > 0) {
                loadTolerance += 0.05;
                log.warn("CP-SAT infeasible on attempt {}, widening load tolerance to {}", attempt, loadTolerance);
            }
            result = trySolve(tileIds, scaledDemand, K, daTargetLoad, loadTolerance,
                    adjacencyGraph, tileIndexMap, seedIndices);
            if (result.status != SolveResult.Status.INFEASIBLE) break;
        }

        if (result == null || result.territories == null) {
            log.warn("CP-SAT failed for city={} date={}, delegating to BFS", cityId, validForDate);
            return bfsFallback.computeProposal(cityId, validForDate, demand, adjacencyGraph, availableDaIds);
        }

        return persistProposal(cityId, validForDate, availableDaIds, demand, tileIds,
                adjacencyGraph, result, daTargetLoad, nTiles, K);
    }

    private SolveResult trySolve(List<UUID> tileIds, long[] scaledDemand, int K,
                                  double daTargetLoad, double loadTolerance,
                                  Map<UUID, List<UUID>> adjacencyGraph,
                                  Map<UUID, Integer> tileIndexMap, int[] seedIndices) {
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
            // Each tile must be assigned to exactly one DA
            Literal[] row = new Literal[K];
            for (int k = 0; k < K; k++) row[k] = b[i][k];
            model.addExactlyOne(row);
        }

        // Load variable per DA — equals weighted sum of demand for assigned tiles
        IntVar[] loads = new IntVar[K];
        for (int k = 0; k < K; k++) {
            loads[k] = model.newIntVar(0, maxPossibleLoad, "load_" + k);
            var expr = LinearExpr.newBuilder();
            for (int i = 0; i < nTiles; i++) expr.addTerm(b[i][k], scaledDemand[i]);
            model.addEquality(loads[k], expr);
            model.addLinearConstraint(loads[k], scaledLb, scaledUb);
        }

        // Objective: minimise max_load − min_load (load spread across DAs)
        IntVar maxLoad = model.newIntVar(0, maxPossibleLoad, "maxLoad");
        IntVar minLoad = model.newIntVar(0, maxPossibleLoad, "minLoad");
        model.addMaxEquality(maxLoad, Arrays.asList(loads));
        model.addMinEquality(minLoad, Arrays.asList(loads));
        model.minimize(LinearExpr.newBuilder().add(maxLoad).addTerm(minLoad, -1L));

        // Symmetry breaking: seed tile k must be assigned to DA k (top-K demand tiles)
        for (int k = 0; k < K; k++) {
            model.addBoolAnd(new Literal[]{b[seedIndices[k]][k]});
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(properties.getSolver().getTimeLimitSeconds());

        // Lazy-cuts loop: solve → check contiguity → add cuts → re-solve
        for (int round = 0; round <= MAX_LAZY_CUT_ROUNDS; round++) {
            CpSolverStatus status = solver.solve(model);

            if (status == CpSolverStatus.INFEASIBLE || status == CpSolverStatus.MODEL_INVALID) {
                return SolveResult.infeasible();
            }
            if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
                return SolveResult.timeout();
            }

            Map<Integer, List<Integer>> territories = extractTerritories(solver, b, nTiles, K);

            if (adjacencyGraph.isEmpty()) {
                // No road data — accept solution without contiguity guarantee
                return new SolveResult(SolveResult.Status.SOLVED, territories,
                        solver.objectiveValue(), solver.bestObjectiveBound());
            }

            boolean allConnected = addContiguityCuts(model, b, territories,
                    tileIds, adjacencyGraph, tileIndexMap, seedIndices, K);

            if (allConnected) {
                return new SolveResult(SolveResult.Status.SOLVED, territories,
                        solver.objectiveValue(), solver.bestObjectiveBound());
            }

            if (round == MAX_LAZY_CUT_ROUNDS) {
                log.warn("CP-SAT lazy-cuts did not converge in {} rounds — using best available solution", MAX_LAZY_CUT_ROUNDS);
                return new SolveResult(SolveResult.Status.SOLVED, territories,
                        solver.objectiveValue(), solver.bestObjectiveBound());
            }
        }

        return SolveResult.infeasible();
    }

    /**
     * Checks contiguity of each DA's territory and adds one cut per disconnected component.
     * Returns true if all territories are already connected (no cuts needed).
     */
    private boolean addContiguityCuts(CpModel model, BoolVar[][] b,
                                       Map<Integer, List<Integer>> territories,
                                       List<UUID> tileIds,
                                       Map<UUID, List<UUID>> adjacencyGraph,
                                       Map<UUID, Integer> tileIndexMap,
                                       int[] seedIndices, int K) {
        boolean allConnected = true;
        for (int k = 0; k < K; k++) {
            List<Integer> territory = territories.getOrDefault(k, List.of());
            if (territory.size() <= 1) continue;

            List<UUID> tileUuids = territory.stream().map(tileIds::get).toList();
            List<List<UUID>> components = ContiguityValidator.findConnectedComponents(tileUuids, adjacencyGraph);
            if (components.size() <= 1) continue;

            allConnected = false;
            UUID seedUuid = tileIds.get(seedIndices[k]);
            // Keep the component that contains the seed tile; cut all others
            List<UUID> seedComponent = components.stream()
                    .filter(c -> c.contains(seedUuid))
                    .findFirst()
                    .orElse(components.get(0));

            for (List<UUID> comp : components) {
                if (comp == seedComponent) continue;
                // Forbid all tiles in this disconnected component from being assigned to DA k
                var cutExpr = LinearExpr.newBuilder();
                for (UUID uuid : comp) cutExpr.add(b[tileIndexMap.get(uuid)][k]);
                model.addLinearConstraint(cutExpr, 0L, (long) comp.size() - 1);
            }
        }
        return allConnected;
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

    private int[] computeSeedIndices(long[] scaledDemand, int K) {
        List<Integer> sorted = new ArrayList<>();
        for (int i = 0; i < scaledDemand.length; i++) sorted.add(i);
        sorted.sort((a, c) -> Long.compare(scaledDemand[c], scaledDemand[a]));
        int[] seeds = new int[K];
        for (int k = 0; k < K; k++) {
            seeds[k] = (k < sorted.size()) ? sorted.get(k) : sorted.get(sorted.size() - 1);
        }
        return seeds;
    }

    private AssignmentProposal persistProposal(UUID cityId, LocalDate validForDate,
                                               List<UUID> availableDaIds,
                                               List<TileDemandSnapshot> demand,
                                               List<UUID> tileIds,
                                               Map<UUID, List<UUID>> adjacencyGraph,
                                               SolveResult result,
                                               double daTargetLoad,
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
                    .mapToDouble(i -> demand.get(i).getDemandScoreMinutes()).sum();
            boolean hasBootstrapped = tileIndices.stream()
                    .anyMatch(i -> bootstrappedMap.getOrDefault(tileIds.get(i), true));

            regions.add(AssignmentProposalRegion.builder()
                    .proposalId(proposal.getId())
                    .daId(daId)
                    .nDasRequired(1)
                    .estimatedDemandMin(totalDemand)
                    .estimatedUtilPct(totalDemand / daTargetLoad)
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

        log.info("CP-SAT proposal {} created: {} DAs, {} tiles, gap={:.1f}%",
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
