package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.domain.TileTravelTime;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.repository.TileTravelTimeRepository;
import com.oneday.grid.service.AssignmentService;
import com.oneday.grid.service.DemandScoringService;
import com.oneday.grid.service.GridReplanService;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.ProposalService;
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
import java.util.UUID;
import java.util.stream.Collectors;

@Service
class GridReplanServiceImpl implements GridReplanService {

    private static final Logger log = LoggerFactory.getLogger(GridReplanServiceImpl.class);
    private static final Duration ADJACENCY_STALE_THRESHOLD = Duration.ofDays(45);

    private final GridService gridService;
    private final TileRepository tileRepository;
    private final TileTravelTimeRepository travelTimeRepository;
    private final DemandScoringService demandScoringService;
    private final AssignmentService cpSatAssignmentService;
    private final AssignmentProposalRepository proposalRepository;
    private final ProposalService proposalService;
    private final GridProperties properties;

    GridReplanServiceImpl(GridService gridService,
                          TileRepository tileRepository,
                          TileTravelTimeRepository travelTimeRepository,
                          DemandScoringService demandScoringService,
                          @Qualifier("cpSatAssignmentService") AssignmentService cpSatAssignmentService,
                          AssignmentProposalRepository proposalRepository,
                          ProposalService proposalService,
                          GridProperties properties) {
        this.gridService = gridService;
        this.tileRepository = tileRepository;
        this.travelTimeRepository = travelTimeRepository;
        this.demandScoringService = demandScoringService;
        this.cpSatAssignmentService = cpSatAssignmentService;
        this.proposalRepository = proposalRepository;
        this.proposalService = proposalService;
        this.properties = properties;
    }

    @Override
    public ProposalResponse replan(UUID cityId, LocalDate validForDate, List<UUID> daIds) {
        log.info("GridReplanService.replan cityId={} date={} daCount={}", cityId, validForDate, daIds.size());

        List<TileDemandSnapshot> demand = demandScoringService.computeAndPersistDemand(cityId, validForDate);
        if (demand.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No active tiles for cityId=" + cityId + "; initialize the grid first");
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
        long multiDaTiles = demand.stream().filter(d -> d.getDemandScoreMinutes() > daMaxLoad).count();
        if (multiDaTiles > 0) {
            log.warn("COMPONENT_C: {} tiles exceed DA_max_load — multi-DA splitting deferred", multiDaTiles);
        }

        AssignmentProposal proposal = cpSatAssignmentService.computeProposal(
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
        List<TileTravelTime> rows = travelTimeRepository
                .findByGridIdAndTravelTimeSecondsLessThanEqual(grid.getId(), threshold);
        if (rows.isEmpty() || isStale(rows)) return null;

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
        log.info("Geometric 4-connectivity built for cityId={}: {} tiles", cityId, tiles.size());
        return graph;
    }
}
