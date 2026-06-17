package com.oneday.grid.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.AssignmentProposalRegion;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.HexTravelTime;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.dto.response.IntradayReassignmentResponse;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.dto.response.RegionResponse;
import com.oneday.grid.dto.response.TileShareResponse;
import com.oneday.grid.repository.AssignmentProposalRegionRepository;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.HexTravelTimeRepository;
import com.oneday.grid.service.ProposalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
class ProposalServiceImpl implements ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalServiceImpl.class);

    private final AssignmentProposalRepository proposalRepository;
    private final AssignmentProposalRegionRepository regionRepository;
    private final DaHexAssignmentRepository assignmentRepository;
    private final GridRepository gridRepository;
    private final HexTravelTimeRepository travelTimeRepository;
    private final GridProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    ProposalServiceImpl(AssignmentProposalRepository proposalRepository,
                        AssignmentProposalRegionRepository regionRepository,
                        DaHexAssignmentRepository assignmentRepository,
                        GridRepository gridRepository,
                        HexTravelTimeRepository travelTimeRepository,
                        GridProperties properties) {
        this.proposalRepository = proposalRepository;
        this.regionRepository = regionRepository;
        this.assignmentRepository = assignmentRepository;
        this.gridRepository = gridRepository;
        this.travelTimeRepository = travelTimeRepository;
        this.properties = properties;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @Override
    public ProposalResponse getProposal(UUID proposalId) {
        AssignmentProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));
        return toResponse(proposal);
    }

    @Override
    public List<ProposalResponse> getProposals(UUID cityId, LocalDate date) {
        return proposalRepository.findByCityIdAndValidForDate(cityId, date).stream()
                .map(this::toResponse)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Nightly proposal lifecycle
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void approve(UUID proposalId, UUID reviewerId) {
        AssignmentProposal proposal = requireProposal(proposalId);
        require(proposal.getStatus() == ProposalStatus.PROPOSED,
                "Cannot approve proposal " + proposalId + " in status " + proposal.getStatus());

        Instant now = Instant.now();

        // Supersede any previously APPROVED proposal + its assignments for the same city+date
        proposalRepository.findByCityIdAndValidForDate(proposal.getCityId(), proposal.getValidForDate())
                .stream()
                .filter(p -> !p.getId().equals(proposalId) && p.getStatus() == ProposalStatus.APPROVED)
                .forEach(existing -> {
                    supersedeAssignments(existing.getId());
                    existing.setStatus(ProposalStatus.SUPERSEDED);
                    proposalRepository.save(existing);
                });

        // Activate this proposal's assignments
        List<DaHexAssignment> assignments = assignmentRepository.findByProposalId(proposalId);
        assignments.forEach(a -> {
            a.setStatus(AssignmentStatus.APPROVED);
            a.setApprovedBy(reviewerId);
            a.setApprovedAt(now);
        });
        assignmentRepository.saveAll(assignments);

        proposal.setStatus(ProposalStatus.APPROVED);
        proposal.setReviewedBy(reviewerId);
        proposal.setReviewedAt(now);
        proposalRepository.save(proposal);

        log.info("Proposal {} approved by {}", proposalId, reviewerId);
    }

    @Override
    @Transactional
    public void reject(UUID proposalId, UUID reviewerId, String notes) {
        AssignmentProposal proposal = requireProposal(proposalId);
        require(proposal.getStatus() == ProposalStatus.PROPOSED,
                "Cannot reject proposal " + proposalId + " in status " + proposal.getStatus());

        proposal.setStatus(ProposalStatus.REJECTED);
        proposal.setReviewedBy(reviewerId);
        proposal.setReviewedAt(Instant.now());
        proposal.setNotes(notes);
        proposalRepository.save(proposal);

        log.info("Proposal {} rejected by {}", proposalId, reviewerId);
    }

    // -------------------------------------------------------------------------
    // Scenario A — pre-approval region edit
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void editRegionInProposal(UUID proposalId, UUID daId, List<UUID> newHexIds, UUID reviewerId) {
        AssignmentProposal proposal = requireProposal(proposalId);
        require(proposal.getStatus() == ProposalStatus.PROPOSED,
                "editRegionInProposal requires PROPOSED status, got: " + proposal.getStatus());

        Map<UUID, List<UUID>> adjacencyGraph = loadAdjacencyGraph(proposal.getCityId());
        require(ContiguityValidator.isConnected(newHexIds, adjacencyGraph),
                "New hex set for DA " + daId + " is not contiguous");

        // Supersede existing PROPOSED assignments for this DA under this proposal
        List<DaHexAssignment> existing = assignmentRepository.findByProposalId(proposalId).stream()
                .filter(a -> a.getDaId().equals(daId) && a.getStatus() == AssignmentStatus.PROPOSED)
                .toList();
        existing.forEach(a -> a.setStatus(AssignmentStatus.SUPERSEDED));
        assignmentRepository.saveAll(existing);

        // Insert new rows for the replacement hex set
        List<DaHexAssignment> replacements = newHexIds.stream()
                .map(hexId -> DaHexAssignment.builder()
                        .proposalId(proposalId)
                        .daId(daId)
                        .hexId(hexId)
                        .validDate(proposal.getValidForDate())
                        .nDasOnHex(1)
                        .status(AssignmentStatus.PROPOSED)
                        .build())
                .toList();
        assignmentRepository.saveAll(replacements);

        // Update region stats
        regionRepository.findByProposalIdAndDaId(proposalId, daId).ifPresent(region -> {
            region.setNDasRequired(1);
            regionRepository.save(region);
        });

        log.info("Region edited in proposal {} for DA {}: {} hexes", proposalId, daId, newHexIds.size());
    }

    // -------------------------------------------------------------------------
    // Scenario B — intraday hex reassignment
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public IntradayReassignmentResponse requestIntradayReassignment(UUID cityId, UUID fromDaId,
                                                                    UUID toDaId,
                                                                    List<UUID> hexIdsToMove,
                                                                    UUID requestedBy) {
        LocalDate today = LocalDate.now();
        Set<UUID> moveSet = new HashSet<>(hexIdsToMove);

        // Load current APPROVED assignments for both DAs
        List<UUID> fromCurrent = activeAssignedHexes(fromDaId, today);
        List<UUID> toCurrent   = activeAssignedHexes(toDaId,   today);

        // Validate all hexes to move are currently assigned to fromDaId
        Set<UUID> fromSet = new HashSet<>(fromCurrent);
        for (UUID hexId : hexIdsToMove) {
            require(fromSet.contains(hexId),
                    "Hex " + hexId + " is not assigned under DA " + fromDaId);
        }

        Map<UUID, List<UUID>> adjacencyGraph = loadAdjacencyGraph(cityId);

        // fromDaId's new territory must remain connected after removing hexes
        List<UUID> fromNew = fromCurrent.stream().filter(t -> !moveSet.contains(t)).toList();
        require(fromNew.isEmpty() || ContiguityValidator.isConnected(fromNew, adjacencyGraph),
                "Removing hexes would disconnect DA " + fromDaId + "'s territory");

        // toDaId's new territory must be connected after adding hexes
        List<UUID> toNew = new ArrayList<>(toCurrent);
        toNew.addAll(hexIdsToMove);
        require(ContiguityValidator.isConnected(toNew, adjacencyGraph),
                "Adding hexes would disconnect DA " + toDaId + "'s territory");

        // Create INTRADAY_OVERRIDE proposal (requires separate approval)
        AssignmentProposal overrideProposal = proposalRepository.save(AssignmentProposal.builder()
                .cityId(cityId)
                .validForDate(today)
                .status(ProposalStatus.PROPOSED)
                .proposalType(ProposalType.INTRADAY_OVERRIDE)
                .solverType(SolverType.MANUAL)
                .adjacencySource(adjacencyGraph.isEmpty()
                        ? com.oneday.grid.domain.AdjacencySource.GEOMETRIC_FALLBACK
                        : com.oneday.grid.domain.AdjacencySource.OSRM)
                .totalDas(2)
                .coveragePct(100.0)
                .understaffedHexIds("[]")
                .build());

        // Write full new hex sets for both affected DAs (append-only — no mutations to active rows)
        List<DaHexAssignment> newAssignments = new ArrayList<>();
        for (UUID hexId : fromNew) {
            newAssignments.add(buildProposedAssignment(overrideProposal.getId(), fromDaId, hexId, today));
        }
        for (UUID hexId : toNew) {
            newAssignments.add(buildProposedAssignment(overrideProposal.getId(), toDaId, hexId, today));
        }
        assignmentRepository.saveAll(newAssignments);

        log.info("Intraday override proposal {} created: {} hexes moved from {} to {}",
                overrideProposal.getId(), hexIdsToMove.size(), fromDaId, toDaId);

        return new IntradayReassignmentResponse(
                overrideProposal.getId(), cityId, fromDaId, toDaId,
                hexIdsToMove, ProposalStatus.PROPOSED, overrideProposal.getProposedAt());
    }

    @Override
    @Transactional
    public void approveIntradayReassignment(UUID proposalId, UUID reviewerId) {
        AssignmentProposal proposal = requireProposal(proposalId);
        require(proposal.getStatus() == ProposalStatus.PROPOSED,
                "Cannot approve override " + proposalId + " in status " + proposal.getStatus());
        require(proposal.getProposalType() == ProposalType.INTRADAY_OVERRIDE,
                "Proposal " + proposalId + " is not an INTRADAY_OVERRIDE");

        Instant now = Instant.now();

        // Collect the DAs affected by this override proposal
        List<DaHexAssignment> overrideAssignments = assignmentRepository.findByProposalId(proposalId);
        Set<UUID> affectedDaIds = overrideAssignments.stream()
                .map(DaHexAssignment::getDaId)
                .collect(Collectors.toSet());

        // Supersede the standing APPROVED assignments for the affected DAs on today's date
        for (UUID daId : affectedDaIds) {
            assignmentRepository.findByDaIdAndValidDate(daId, proposal.getValidForDate()).stream()
                    .filter(a -> a.getStatus() == AssignmentStatus.APPROVED)
                    .forEach(a -> {
                        a.setStatus(AssignmentStatus.SUPERSEDED);
                        assignmentRepository.save(a);
                    });
        }

        // Approve the override's new assignments (they become the standing plan for the date)
        overrideAssignments.forEach(a -> {
            a.setStatus(AssignmentStatus.APPROVED);
            a.setApprovedBy(reviewerId);
            a.setApprovedAt(now);
        });
        assignmentRepository.saveAll(overrideAssignments);

        proposal.setStatus(ProposalStatus.APPROVED);
        proposal.setReviewedBy(reviewerId);
        proposal.setReviewedAt(now);
        proposalRepository.save(proposal);

        log.info("Intraday override {} approved by {}", proposalId, reviewerId);
    }

    // -------------------------------------------------------------------------
    // Hex share
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public TileShareResponse requestTileShare(UUID cityId, UUID daId, UUID hexId, UUID requestedBy) {
        LocalDate today = LocalDate.now();

        // Validate the hex is currently assigned (at least one DA covers it)
        List<DaHexAssignment> active = assignmentRepository
                .findByHexIdAndValidDateAndStatus(hexId, today, AssignmentStatus.APPROVED);
        require(!active.isEmpty(), "Hex " + hexId + " has no APPROVED assignment to share");

        AssignmentProposal shareProposal = proposalRepository.save(AssignmentProposal.builder()
                .cityId(cityId)
                .validForDate(today)
                .status(ProposalStatus.PROPOSED)
                .proposalType(ProposalType.INTRADAY_SHARE)
                .solverType(SolverType.MANUAL)
                .adjacencySource(com.oneday.grid.domain.AdjacencySource.OSRM)
                .totalDas(1)
                .coveragePct(100.0)
                .understaffedHexIds("[]")
                .build());

        DaHexAssignment shareAssignment = assignmentRepository.save(DaHexAssignment.builder()
                .proposalId(shareProposal.getId())
                .daId(daId)
                .hexId(hexId)
                .validDate(today)
                .nDasOnHex(active.size() + 1)
                .status(AssignmentStatus.PROPOSED)
                .build());

        log.info("Hex share proposal {} created for hex {} with DA {}", shareProposal.getId(), hexId, daId);

        return new TileShareResponse(shareProposal.getId(), daId, hexId,
                ProposalStatus.PROPOSED, shareAssignment.getProposedAt());
    }

    @Override
    @Transactional
    public void approveTileShare(UUID proposalId, UUID reviewerId) {
        AssignmentProposal proposal = requireProposal(proposalId);
        require(proposal.getStatus() == ProposalStatus.PROPOSED,
                "Cannot approve tile share " + proposalId + " in status " + proposal.getStatus());
        require(proposal.getProposalType() == ProposalType.INTRADAY_SHARE,
                "Proposal " + proposalId + " is not an INTRADAY_SHARE");

        Instant now = Instant.now();

        List<DaHexAssignment> shareAssignments = assignmentRepository.findByProposalId(proposalId);
        shareAssignments.forEach(a -> {
            a.setStatus(AssignmentStatus.APPROVED);
            a.setApprovedBy(reviewerId);
            a.setApprovedAt(now);
        });
        assignmentRepository.saveAll(shareAssignments);

        proposal.setStatus(ProposalStatus.APPROVED);
        proposal.setReviewedBy(reviewerId);
        proposal.setReviewedAt(now);
        proposalRepository.save(proposal);

        log.info("Hex share {} approved by {}", proposalId, reviewerId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<UUID, List<UUID>> loadAdjacencyGraph(UUID cityId) {
        Grid grid = gridRepository.findByCityId(cityId)
                .orElseThrow(() -> new IllegalArgumentException("Grid not found for city: " + cityId));
        List<HexTravelTime> travelTimes = travelTimeRepository
                .findByH3GridIdAndTravelTimeSecondsLessThanEqual(
                        grid.getId(), properties.getOsrm().getAdjacencyThresholdSeconds());
        Map<UUID, List<UUID>> graph = new HashMap<>();
        for (HexTravelTime tt : travelTimes) {
            graph.computeIfAbsent(tt.getFromHexId(), k -> new ArrayList<>()).add(tt.getToHexId());
        }
        return graph;
    }

    private List<UUID> activeAssignedHexes(UUID daId, LocalDate date) {
        return assignmentRepository.findByDaIdAndValidDate(daId, date).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.APPROVED)
                .map(DaHexAssignment::getHexId)
                .toList();
    }

    private void supersedeAssignments(UUID proposalId) {
        List<DaHexAssignment> assignments = assignmentRepository.findByProposalId(proposalId);
        assignments.forEach(a -> a.setStatus(AssignmentStatus.SUPERSEDED));
        assignmentRepository.saveAll(assignments);
    }

    private DaHexAssignment buildProposedAssignment(UUID proposalId, UUID daId, UUID hexId, LocalDate date) {
        return DaHexAssignment.builder()
                .proposalId(proposalId)
                .daId(daId)
                .hexId(hexId)
                .validDate(date)
                .nDasOnHex(1)
                .status(AssignmentStatus.PROPOSED)
                .build();
    }

    private ProposalResponse toResponse(AssignmentProposal proposal) {
        List<AssignmentProposalRegion> regions = regionRepository.findByProposalId(proposal.getId());
        // Fetch the proposal's assignments ONCE and group by DA, instead of re-querying all ~3.5k
        // rows per region. Over a remote DB that loop-invariant query was K (=DA count) sequential
        // round-trips — ~60s for a 200-DA proposal; grouping in memory makes it a single query.
        Map<UUID, List<UUID>> hexIdsByDa = assignmentRepository.findByProposalId(proposal.getId()).stream()
                .filter(a -> a.getStatus() != AssignmentStatus.SUPERSEDED)
                .collect(Collectors.groupingBy(DaHexAssignment::getDaId,
                        Collectors.mapping(DaHexAssignment::getHexId, Collectors.toList())));
        List<RegionResponse> regionResponses = regions.stream()
                .map(r -> new RegionResponse(r.getId(), r.getDaId(), r.getNDasRequired(),
                        r.getEstimatedDemandMin(), r.getEstimatedUtilPct(),
                        r.isHasBootstrappedTiles(),
                        hexIdsByDa.getOrDefault(r.getDaId(), List.of())))
                .toList();

        return new ProposalResponse(
                proposal.getId(), proposal.getCityId(), proposal.getValidForDate(),
                proposal.getStatus(), proposal.getProposalType(), proposal.getSolverType(),
                proposal.getAdjacencySource(), proposal.getOptimalityGapPct(),
                proposal.getTotalDas(), proposal.getCoveragePct(),
                deserializeUuids(proposal.getUnderstaffedHexIds()),
                proposal.getProposedAt(), proposal.getReviewedBy(), proposal.getReviewedAt(),
                proposal.getNotes(), regionResponses);
    }

    private List<UUID> deserializeUuids(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return List.of();
        try {
            List<String> strings = objectMapper.readValue(json, new TypeReference<>() {});
            return strings.stream().map(UUID::fromString).toList();
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private AssignmentProposal requireProposal(UUID proposalId) {
        return proposalRepository.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
