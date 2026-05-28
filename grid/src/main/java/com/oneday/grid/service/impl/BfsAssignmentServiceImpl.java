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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Qualifier("bfsAssignmentService")
public class BfsAssignmentServiceImpl implements AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(BfsAssignmentServiceImpl.class);

    private final AssignmentProposalRepository proposalRepository;
    private final AssignmentProposalRegionRepository regionRepository;
    private final DaHexAssignmentRepository assignmentRepository;
    private final GridProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    BfsAssignmentServiceImpl(AssignmentProposalRepository proposalRepository,
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
    public AssignmentProposal computeProposal(UUID cityId,
                                              LocalDate validForDate,
                                              List<HexDemandSnapshot> demand,
                                              Map<UUID, List<UUID>> adjacencyGraph,
                                              List<UUID> availableDaIds) {
        Map<UUID, Double> demandMap = demand.stream()
                .collect(Collectors.toMap(HexDemandSnapshot::getHexId, HexDemandSnapshot::getDemandScoreMinutes));
        Map<UUID, Boolean> bootstrappedMap = demand.stream()
                .collect(Collectors.toMap(HexDemandSnapshot::getHexId, HexDemandSnapshot::isBootstrapped));

        int shiftMin = (properties.getShift().getEndHour() - properties.getShift().getStartHour()) * 60;
        double daCapacity = shiftMin * properties.getDa().getTargetUtilisation();

        Set<UUID> unassigned = new HashSet<>(demandMap.keySet());
        int K = availableDaIds.size();

        double totalDemandMinutes = demandMap.values().stream().mapToDouble(Double::doubleValue).sum();
        double daTargetLoad = totalDemandMinutes > 0 ? totalDemandMinutes / K : daCapacity;

        Map<UUID, List<UUID>> territories = new LinkedHashMap<>();

        for (int k = 0; k < K && !unassigned.isEmpty(); k++) {
            UUID daId = availableDaIds.get(k);
            List<UUID> territory = new ArrayList<>();
            territories.put(daId, territory);

            UUID seed = unassigned.stream()
                    .max(Comparator.comparingDouble(id -> demandMap.getOrDefault(id, 0.0)))
                    .orElse(null);
            if (seed == null) break;

            PriorityQueue<UUID> frontier = new PriorityQueue<>(
                    Comparator.comparingDouble((UUID id) -> demandMap.getOrDefault(id, 0.0)).reversed());
            Set<UUID> inFrontier = new HashSet<>();
            frontier.add(seed);
            inFrontier.add(seed);

            double load = 0.0;

            while (!frontier.isEmpty()) {
                UUID hex = frontier.poll();
                inFrontier.remove(hex);
                if (!unassigned.contains(hex)) continue;

                if (!territory.isEmpty()) {
                    boolean adjacent = adjacencyGraph.getOrDefault(hex, List.of())
                            .stream().anyMatch(territory::contains);
                    if (!adjacent) continue;
                }

                double hexLoad = demandMap.getOrDefault(hex, 0.0);

                territory.add(hex);
                unassigned.remove(hex);
                load += hexLoad;

                if (load >= daTargetLoad) break;

                for (UUID neighbor : adjacencyGraph.getOrDefault(hex, List.of())) {
                    if (unassigned.contains(neighbor) && inFrontier.add(neighbor)) {
                        frontier.add(neighbor);
                    }
                }
            }
        }

        List<UUID> understaffedHexes = new ArrayList<>(unassigned);
        if (!understaffedHexes.isEmpty()) {
            log.warn("BFS: {} hexes understaffed after assigning {} DAs", understaffedHexes.size(), K);
        }

        AdjacencySource adjacencySource = adjacencyGraph.isEmpty()
                ? AdjacencySource.GEOMETRIC_FALLBACK
                : AdjacencySource.OSRM;

        AssignmentProposal proposal = proposalRepository.save(AssignmentProposal.builder()
                .cityId(cityId)
                .validForDate(validForDate)
                .status(ProposalStatus.PROPOSED)
                .solverType(SolverType.BFS_FALLBACK)
                .adjacencySource(adjacencySource)
                .optimalityGapPct(null)
                .totalDas(territories.size())
                .coveragePct(demandMap.isEmpty() ? 0.0 :
                        (double) (demandMap.size() - understaffedHexes.size()) / demandMap.size() * 100.0)
                .understaffedHexIds(serializeUuids(understaffedHexes))
                .build());

        List<AssignmentProposalRegion> regions = new ArrayList<>();
        List<DaHexAssignment> assignments = new ArrayList<>();

        for (Map.Entry<UUID, List<UUID>> entry : territories.entrySet()) {
            UUID daId = entry.getKey();
            List<UUID> hexIds = entry.getValue();
            if (hexIds.isEmpty()) continue;

            double totalDemand = hexIds.stream().mapToDouble(id -> demandMap.getOrDefault(id, 0.0)).sum();
            boolean hasBootstrapped = hexIds.stream().anyMatch(id -> bootstrappedMap.getOrDefault(id, true));

            regions.add(AssignmentProposalRegion.builder()
                    .proposalId(proposal.getId())
                    .daId(daId)
                    .nDasRequired(1)
                    .estimatedDemandMin(totalDemand)
                    .estimatedUtilPct(totalDemand / daCapacity)
                    .hasBootstrappedTiles(hasBootstrapped)
                    .build());

            for (UUID hexId : hexIds) {
                assignments.add(DaHexAssignment.builder()
                        .proposalId(proposal.getId())
                        .daId(daId)
                        .hexId(hexId)
                        .validDate(validForDate)
                        .nDasOnHex(1)
                        .status(AssignmentStatus.PROPOSED)
                        .build());
            }
        }

        regionRepository.saveAll(regions);
        assignmentRepository.saveAll(assignments);

        log.info("BFS proposal {} created: {} DAs, {} hexes assigned, {} understaffed",
                proposal.getId(), territories.size(),
                assignments.size(), understaffedHexes.size());

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
