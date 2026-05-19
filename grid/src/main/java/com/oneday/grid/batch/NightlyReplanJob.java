package com.oneday.grid.batch;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.AssignmentProposalRegion;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaTileAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.domain.TileTravelTime;
import com.oneday.grid.repository.AssignmentProposalRegionRepository;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaTileAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.repository.TileTravelTimeRepository;
import com.oneday.grid.service.AssignmentService;
import com.oneday.grid.service.DemandScoringService;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// Nightly replan: runs at 01:00 IST, persists a PROPOSED AssignmentProposal for each city.
// Station manager approves by 07:00; if not, auto-fallback applies yesterday's plan.
@Component
public class NightlyReplanJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyReplanJob.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // Trigger OSRM refresh if stored matrix is older than this.
    private static final Duration ADJACENCY_STALE_THRESHOLD = Duration.ofDays(45);

    private final GridRepository gridRepository;
    private final GridService gridService;
    private final TileRepository tileRepository;
    private final TileTravelTimeRepository travelTimeRepository;
    private final DemandScoringService demandScoringService;
    private final AssignmentService cpSatAssignmentService;
    private final AssignmentProposalRepository proposalRepository;
    private final AssignmentProposalRegionRepository proposalRegionRepository;
    private final DaTileAssignmentRepository assignmentRepository;
    private final OsrmMatrixRefreshJob osrmMatrixRefreshJob;
    private final DaRosterPort daRosterPort;
    private final GridProperties properties;

    NightlyReplanJob(GridRepository gridRepository,
                     GridService gridService,
                     TileRepository tileRepository,
                     TileTravelTimeRepository travelTimeRepository,
                     DemandScoringService demandScoringService,
                     @Qualifier("cpSatAssignmentService") AssignmentService cpSatAssignmentService,
                     AssignmentProposalRepository proposalRepository,
                     AssignmentProposalRegionRepository proposalRegionRepository,
                     DaTileAssignmentRepository assignmentRepository,
                     OsrmMatrixRefreshJob osrmMatrixRefreshJob,
                     DaRosterPort daRosterPort,
                     GridProperties properties) {
        this.gridRepository = gridRepository;
        this.gridService = gridService;
        this.tileRepository = tileRepository;
        this.travelTimeRepository = travelTimeRepository;
        this.demandScoringService = demandScoringService;
        this.cpSatAssignmentService = cpSatAssignmentService;
        this.proposalRepository = proposalRepository;
        this.proposalRegionRepository = proposalRegionRepository;
        this.assignmentRepository = assignmentRepository;
        this.osrmMatrixRefreshJob = osrmMatrixRefreshJob;
        this.daRosterPort = daRosterPort;
        this.properties = properties;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Kolkata")
    public void run() {
        LocalDate tomorrow = LocalDate.now(IST).plusDays(1);
        log.info("NightlyReplanJob starting for date={}", tomorrow);
        for (Grid grid : gridRepository.findAll()) {
            try {
                replanForCity(grid.getCityId(), tomorrow);
            } catch (Exception e) {
                log.error("NightlyReplanJob failed for cityId={}", grid.getCityId(), e);
            }
        }
        log.info("NightlyReplanJob complete for date={}", tomorrow);
    }

    // Escalation check at 06:00 IST: if no approved proposal for today, emit a warning.
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
    public void checkEscalation() {
        LocalDate today = LocalDate.now(IST);
        for (Grid grid : gridRepository.findAll()) {
            UUID cityId = grid.getCityId();
            Optional<AssignmentProposal> approved = proposalRepository
                    .findByCityIdAndValidForDateAndStatus(cityId, today, ProposalStatus.APPROVED);
            if (approved.isEmpty()) {
                log.warn("ESCALATION_ALERT cityId={} date={}: no approved proposal by 06:00; station manager action required", cityId, today);
            }
        }
    }

    // Auto-fallback at 07:00 IST: if still no approved proposal, copy yesterday's active assignments.
    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void applyFallbackIfNeeded() {
        LocalDate today = LocalDate.now(IST);
        LocalDate yesterday = today.minusDays(1);
        for (Grid grid : gridRepository.findAll()) {
            UUID cityId = grid.getCityId();
            Optional<AssignmentProposal> approved = proposalRepository
                    .findByCityIdAndValidForDateAndStatus(cityId, today, ProposalStatus.APPROVED);
            if (approved.isPresent()) continue;

            Optional<AssignmentProposal> yesterdayProposal = proposalRepository
                    .findByCityIdAndValidForDateAndStatus(cityId, yesterday, ProposalStatus.APPROVED);
            if (yesterdayProposal.isEmpty()) {
                log.warn("AUTO_FALLBACK_FAILED cityId={}: no approved proposal for yesterday {} either; city has no coverage", cityId, yesterday);
                continue;
            }

            applyFallback(cityId, today, yesterdayProposal.get());
        }
    }

    private void replanForCity(UUID cityId, LocalDate validForDate) {
        log.info("NightlyReplanJob replanForCity cityId={} date={}", cityId, validForDate);

        // Step 1: Demand scoring
        List<TileDemandSnapshot> demand = demandScoringService.computeAndPersistDemand(cityId, validForDate);
        if (demand.isEmpty()) {
            log.warn("No active tiles for cityId={}; skipping replan", cityId);
            return;
        }

        // Step 2: Load or refresh road-adjacency graph
        Grid grid = gridService.getGrid(cityId);
        AdjacencySource adjacencySource = AdjacencySource.OSRM;
        Map<UUID, List<UUID>> adjacencyGraph = loadAdjacencyGraph(grid, cityId);
        if (adjacencyGraph == null) {
            // OSRM refresh failed — fall back to geometric 4-connectivity
            adjacencyGraph = buildGeometricAdjacency(cityId, grid);
            adjacencySource = AdjacencySource.GEOMETRIC_FALLBACK;
            log.warn("GEOMETRIC_FALLBACK activated for cityId={}", cityId);
        }

        // Step 3: Component C — multi-DA tile pre-processing
        // Tiles whose demand exceeds DA_max_load need multiple DAs.
        // Full virtual-tile splitting is deferred; log and continue for now.
        double shiftMinutes = (properties.getShift().getEndHour() - properties.getShift().getStartHour()) * 60.0;
        double daMaxLoad = shiftMinutes * properties.getDa().getMaxUtilisation();
        double daTargetLoad = shiftMinutes * properties.getDa().getTargetUtilisation();
        long multiDaTiles = demand.stream().filter(d -> d.getDemandScoreMinutes() > daMaxLoad).count();
        if (multiDaTiles > 0) {
            log.warn("COMPONENT_C: {} tiles in cityId={} exceed DA_max_load={:.0f}min — multi-DA splitting not yet implemented; solver will flag as understaffed",
                    multiDaTiles, cityId, daMaxLoad);
        }

        // Step 4: DA availability
        List<UUID> availableDaIds = daRosterPort.getAvailableDaIds(cityId, validForDate);
        double totalDemandMinutes = demand.stream().mapToDouble(TileDemandSnapshot::getDemandScoreMinutes).sum();
        int kNeeded = (int) Math.ceil(totalDemandMinutes / daTargetLoad);
        int kAvailable = availableDaIds.size();
        if (kAvailable < kNeeded) {
            log.warn("UNDERSTAFFED cityId={} date={}: K_available={} K_needed={}", cityId, validForDate, kAvailable, kNeeded);
        }

        // Step 5: Run CP-SAT solver (with BFS fallback built in)
        AssignmentProposal proposal = cpSatAssignmentService.computeProposal(
                cityId, validForDate, demand, adjacencyGraph, availableDaIds);

        // Force adjacency source onto the persisted proposal (solver doesn't know about fallback).
        if (adjacencySource == AdjacencySource.GEOMETRIC_FALLBACK
                && proposal.getAdjacencySource() != AdjacencySource.GEOMETRIC_FALLBACK) {
            proposal.setAdjacencySource(AdjacencySource.GEOMETRIC_FALLBACK);
            proposalRepository.save(proposal);
        }

        // Step 6: Notification (placeholder — Phase 7/8 will wire a real channel)
        log.info("PROPOSAL_READY cityId={} date={} proposalId={} solverType={} totalDas={}",
                cityId, validForDate, proposal.getId(), proposal.getSolverType(), proposal.getTotalDas());
    }

    // Returns null if OSRM refresh fails or adjacency is still unavailable after refresh.
    private Map<UUID, List<UUID>> loadAdjacencyGraph(Grid grid, UUID cityId) {
        int threshold = properties.getOsrm().getAdjacencyThresholdSeconds();
        List<TileTravelTime> rows = travelTimeRepository
                .findByGridIdAndTravelTimeSecondsLessThanEqual(grid.getId(), threshold);

        boolean stale = rows.isEmpty() || isStale(rows);
        if (stale) {
            log.info("Adjacency matrix for cityId={} is {} — triggering OSRM refresh",
                    cityId, rows.isEmpty() ? "absent" : "stale (>45 days)");
            try {
                osrmMatrixRefreshJob.refresh(cityId);
                rows = travelTimeRepository.findByGridIdAndTravelTimeSecondsLessThanEqual(grid.getId(), threshold);
            } catch (Exception e) {
                log.error("OSRM refresh failed for cityId={}", cityId, e);
                return null;
            }
        }

        if (rows.isEmpty()) return null;

        Map<UUID, List<UUID>> graph = new HashMap<>();
        for (TileTravelTime row : rows) {
            graph.computeIfAbsent(row.getFromTileId(), k -> new ArrayList<>()).add(row.getToTileId());
        }
        return graph;
    }

    private boolean isStale(List<TileTravelTime> rows) {
        Instant oldest = rows.stream().map(TileTravelTime::getComputedAt).min(Instant::compareTo).orElse(Instant.now());
        return Duration.between(oldest, Instant.now()).compareTo(ADJACENCY_STALE_THRESHOLD) > 0;
    }

    // Geometric 4-connectivity: two tiles are adjacent iff they share an edge (|Δrow|+|Δcol| == 1).
    private Map<UUID, List<UUID>> buildGeometricAdjacency(UUID cityId, Grid grid) {
        List<Tile> tiles = tileRepository.findByGridIdAndActiveTrue(grid.getId());
        Map<String, UUID> tileIndex = tiles.stream()
                .collect(Collectors.toMap(t -> t.getRowIdx() + "," + t.getColIdx(), Tile::getId));

        int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        Map<UUID, List<UUID>> graph = new HashMap<>();
        for (Tile tile : tiles) {
            List<UUID> neighbors = new ArrayList<>();
            for (int[] off : offsets) {
                UUID neighborId = tileIndex.get((tile.getRowIdx() + off[0]) + "," + (tile.getColIdx() + off[1]));
                if (neighborId != null) neighbors.add(neighborId);
            }
            graph.put(tile.getId(), neighbors);
        }
        log.info("Built geometric 4-connectivity adjacency for cityId={}: {} tiles", cityId, tiles.size());
        return graph;
    }

    @Transactional
    void applyFallback(UUID cityId, LocalDate today, AssignmentProposal yesterdayProposal) {
        List<DaTileAssignment> yesterdayActive = assignmentRepository
                .findByProposalId(yesterdayProposal.getId())
                .stream().filter(a -> a.getStatus() == AssignmentStatus.ACTIVE).toList();

        long daCount = yesterdayActive.stream().map(DaTileAssignment::getDaId).distinct().count();

        AssignmentProposal fallback = AssignmentProposal.builder()
                .cityId(cityId)
                .validForDate(today)
                .status(ProposalStatus.APPROVED)
                .proposalType(ProposalType.NIGHTLY)
                .solverType(SolverType.MANUAL)
                .adjacencySource(yesterdayProposal.getAdjacencySource())
                .totalDas((int) daCount)
                .notes("Auto-fallback: no approved proposal by 07:00; copied from " + today.minusDays(1))
                .build();
        fallback = proposalRepository.save(fallback);

        UUID fallbackId = fallback.getId();
        List<DaTileAssignment> todayAssignments = yesterdayActive.stream()
                .map(a -> DaTileAssignment.builder()
                        .proposalId(fallbackId)
                        .daId(a.getDaId())
                        .tileId(a.getTileId())
                        .validDate(today)
                        .nDasOnTile(a.getNDasOnTile())
                        .status(AssignmentStatus.ACTIVE)
                        .build())
                .collect(Collectors.toList());
        assignmentRepository.saveAll(todayAssignments);

        List<AssignmentProposalRegion> yesterdayRegions = proposalRegionRepository
                .findByProposalId(yesterdayProposal.getId());
        List<AssignmentProposalRegion> todayRegions = yesterdayRegions.stream()
                .map(r -> AssignmentProposalRegion.builder()
                        .proposalId(fallbackId)
                        .daId(r.getDaId())
                        .nDasRequired(r.getNDasRequired())
                        .estimatedDemandMin(r.getEstimatedDemandMin())
                        .estimatedUtilPct(r.getEstimatedUtilPct())
                        .hasBootstrappedTiles(r.isHasBootstrappedTiles())
                        .build())
                .collect(Collectors.toList());
        proposalRegionRepository.saveAll(todayRegions);

        log.info("AUTO_FALLBACK_APPLIED cityId={} date={} proposalId={} assignments={}",
                cityId, today, fallbackId, todayAssignments.size());
    }
}
