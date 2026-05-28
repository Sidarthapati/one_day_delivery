package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.domain.HexDemandSnapshot;
import com.oneday.grid.domain.HexTravelTime;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.repository.HexTravelTimeRepository;
import com.oneday.grid.service.AssignmentService;
import com.oneday.grid.service.DemandScoringService;
import com.oneday.grid.service.GridReplanService;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.ProposalService;
import com.uber.h3core.H3Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
class GridReplanServiceImpl implements GridReplanService {

    private static final Logger log = LoggerFactory.getLogger(GridReplanServiceImpl.class);
    private static final Duration ADJACENCY_STALE_THRESHOLD = Duration.ofDays(45);

    private final GridService gridService;
    private final HexRepository hexRepository;
    private final HexTravelTimeRepository travelTimeRepository;
    private final DemandScoringService demandScoringService;
    private final AssignmentService assignmentService;
    private final AssignmentProposalRepository proposalRepository;
    private final ProposalService proposalService;
    private final GridProperties properties;
    private final H3Core h3Core;

    GridReplanServiceImpl(GridService gridService,
                          HexRepository hexRepository,
                          HexTravelTimeRepository travelTimeRepository,
                          DemandScoringService demandScoringService,
                          @Qualifier("cpSatAssignmentService") AssignmentService assignmentService,
                          AssignmentProposalRepository proposalRepository,
                          ProposalService proposalService,
                          GridProperties properties,
                          H3Core h3Core) {
        this.gridService = gridService;
        this.hexRepository = hexRepository;
        this.travelTimeRepository = travelTimeRepository;
        this.demandScoringService = demandScoringService;
        this.assignmentService = assignmentService;
        this.proposalRepository = proposalRepository;
        this.proposalService = proposalService;
        this.properties = properties;
        this.h3Core = h3Core;
    }

    @Override
    public ProposalResponse replan(UUID cityId, LocalDate validForDate, List<UUID> daIds) {
        log.info("GridReplanService.replan cityId={} date={} daCount={}", cityId, validForDate, daIds.size());

        List<HexDemandSnapshot> demand = demandScoringService.computeAndPersistDemand(cityId, validForDate);
        if (demand.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No active hexes for cityId=" + cityId + "; initialize the grid first");
        }

        Grid grid = gridService.getGrid(cityId);
        AdjacencySource adjacencySource = AdjacencySource.OSRM;
        Map<UUID, List<UUID>> adjacencyGraph = loadAdjacencyGraph(grid);
        if (adjacencyGraph == null) {
            adjacencyGraph = buildGeometricAdjacency(cityId, grid);
            adjacencySource = AdjacencySource.GEOMETRIC_FALLBACK;
            log.warn("GEOMETRIC_FALLBACK activated for cityId={} — OSRM matrix absent or stale", cityId);
        }

        double shiftMinutes = (properties.getShift().getEndHour() - properties.getShift().getStartHour()) * 60.0;
        double daMaxLoad = shiftMinutes * properties.getDa().getMaxUtilisation();
        long multiDaHexes = demand.stream().filter(d -> d.getDemandScoreMinutes() > daMaxLoad).count();
        if (multiDaHexes > 0) {
            log.warn("COMPONENT_C: {} hexes exceed DA_max_load — multi-DA splitting deferred", multiDaHexes);
        }

        AssignmentProposal proposal = assignmentService.computeProposal(
                cityId, validForDate, demand, adjacencyGraph, daIds);

        if (adjacencySource == AdjacencySource.GEOMETRIC_FALLBACK
                && proposal.getAdjacencySource() != AdjacencySource.GEOMETRIC_FALLBACK) {
            proposal.setAdjacencySource(AdjacencySource.GEOMETRIC_FALLBACK);
            proposalRepository.save(proposal);
        }

        log.info("PROPOSAL_READY cityId={} date={} proposalId={} solver={} das={}",
                cityId, validForDate, proposal.getId(), proposal.getSolverType(), proposal.getTotalDas());
        return proposalService.getProposal(proposal.getId());
    }

    private Map<UUID, List<UUID>> loadAdjacencyGraph(Grid grid) {
        int threshold = properties.getOsrm().getAdjacencyThresholdSeconds();
        List<HexTravelTime> rows = travelTimeRepository
                .findByH3GridIdAndTravelTimeSecondsLessThanEqual(grid.getId(), threshold);
        if (rows.isEmpty() || isStale(rows)) return null;

        Map<UUID, List<UUID>> graph = new HashMap<>();
        for (HexTravelTime row : rows) {
            graph.computeIfAbsent(row.getFromHexId(), k -> new ArrayList<>()).add(row.getToHexId());
        }
        return graph;
    }

    private boolean isStale(List<HexTravelTime> rows) {
        Instant oldest = rows.stream().map(HexTravelTime::getComputedAt).min(Instant::compareTo).orElse(Instant.now());
        return Duration.between(oldest, Instant.now()).compareTo(ADJACENCY_STALE_THRESHOLD) > 0;
    }

    private Map<UUID, List<UUID>> buildGeometricAdjacency(UUID cityId, Grid grid) {
        List<Hex> hexes = hexRepository.findByH3GridIdAndActiveTrue(grid.getId());
        Set<Long> activeH3Indices = hexes.stream()
                .map(Hex::getH3Index).collect(Collectors.toSet());
        Map<Long, UUID> h3ToId = hexes.stream()
                .collect(Collectors.toMap(Hex::getH3Index, Hex::getId));

        Map<UUID, List<UUID>> graph = new HashMap<>();
        for (Hex hex : hexes) {
            long centerH3 = hex.getH3Index();
            List<Long> disk = h3Core.gridDisk(centerH3, 1);
            List<UUID> neighbors = disk.stream()
                    .filter(h -> h != centerH3 && activeH3Indices.contains(h))
                    .map(h3ToId::get)
                    .toList();
            graph.put(hex.getId(), neighbors);
        }
        log.info("Geometric H3 1-ring adjacency built for cityId={}: {} hexes", cityId, hexes.size());
        return graph;
    }
}
