package com.oneday.grid.service.impl;

import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.dto.response.AbsenceReassignmentPlan;
import com.oneday.grid.dto.response.AbsenceReassignmentPlan.HexReassignment;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.HexRepository;
import com.uber.h3core.H3Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Territory-split planner for midday DA absence (M3). The <b>only</b> decision is which neighbor
 * inherits each hex left uncovered by an absent DA; once split, M5 makes every task follow its hex to
 * the new owner (no per-task feasibility gate — {@code QueueReorderService} handles cron ordering).
 *
 * <p>A hex needs re-cover only when <em>every</em> DA owning it is absent (a shared hex with a live
 * co-owner is already covered). Its receiver is the H3 1-ring adjacent DA with the most spare capacity
 * — approximated by fewest currently-assigned hexes (capacity is a soft preference, §5), ties broken
 * by the strongest shared border then DA id for determinism. Because a receiver is chosen only from
 * DAs bordering the hex, adding the hex keeps that receiver's territory connected — no separate
 * contiguity repair needed. Hexes with no live neighbor are {@code orphans} for manual escalation.</p>
 */
@Service
class AbsenceReassignmentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AbsenceReassignmentPlanner.class);

    private final DaHexAssignmentRepository assignmentRepository;
    private final AssignmentProposalRepository proposalRepository;
    private final GridRepository gridRepository;
    private final HexRepository hexRepository;
    private final H3Core h3Core;

    AbsenceReassignmentPlanner(DaHexAssignmentRepository assignmentRepository,
                              AssignmentProposalRepository proposalRepository,
                              GridRepository gridRepository,
                              HexRepository hexRepository,
                              H3Core h3Core) {
        this.assignmentRepository = assignmentRepository;
        this.proposalRepository = proposalRepository;
        this.gridRepository = gridRepository;
        this.hexRepository = hexRepository;
        this.h3Core = h3Core;
    }

    /**
     * Compute the split without writing anything (advisory preview). Balanced multi-source region
     * growing: neighbors grow inward from the vacated territory's border. Each round the globally
     * least-loaded live DA that borders any still-unclaimed vacated hex claims one adjacent hex (the
     * one it shares the most border with). Since every claim glues onto a hex the claimer already
     * owns — original or just-claimed — each receiver stays contiguous; growing the least-loaded one
     * first spreads the load across <em>all</em> the vacated territory's neighbors. A vacated hex with
     * no path to any live DA (an isolated pocket) is left an orphan.
     */
    AbsenceReassignmentPlan plan(UUID cityId, List<UUID> absentDaIds, LocalDate date) {
        Set<UUID> absent = new HashSet<>(absentDaIds);
        Grid grid = gridRepository.findByCityId(cityId)
                .orElseThrow(() -> new IllegalArgumentException("Grid not found for city: " + cityId));

        List<Hex> hexes = hexRepository.findByH3GridIdAndActiveTrue(grid.getId());
        Set<UUID> gridHexIds = hexes.stream().map(Hex::getId).collect(Collectors.toSet());
        Map<UUID, List<UUID>> adjacency = geometricAdjacency(hexes);

        // Current standing plan for the date, scoped to this city's hexes.
        List<DaHexAssignment> approved = assignmentRepository.findByValidDateAndStatus(date, AssignmentStatus.APPROVED)
                .stream().filter(a -> gridHexIds.contains(a.getHexId())).toList();

        // Live ownership (hex → a live DA) grows as hexes are claimed; load is the balancing counter.
        Map<UUID, UUID> owner = new HashMap<>();
        Map<UUID, Integer> load = new HashMap<>();
        Map<UUID, UUID> absentOwner = new HashMap<>();   // hex → an absent owner (the fromDa)
        for (DaHexAssignment a : approved) {
            if (absent.contains(a.getDaId())) {
                absentOwner.putIfAbsent(a.getHexId(), a.getDaId());
            } else {
                owner.putIfAbsent(a.getHexId(), a.getDaId());
                load.merge(a.getDaId(), 1, Integer::sum);
            }
        }

        // Vacated = owned by an absent DA and by no live co-owner (a shared hex with a live owner is fine).
        Set<UUID> unclaimed = absentOwner.keySet().stream()
                .filter(h -> !owner.containsKey(h))
                .collect(Collectors.toCollection(java.util.TreeSet::new));

        List<HexReassignment> reassignments = new ArrayList<>();
        while (!unclaimed.isEmpty()) {
            // Which live DAs can currently claim which unclaimed hexes (adjacency to a hex they own).
            Map<UUID, List<UUID>> claimableByDa = new HashMap<>();
            for (UUID h : unclaimed) {
                Set<UUID> adjOwners = new HashSet<>();
                for (UUID nb : adjacency.getOrDefault(h, List.of())) {
                    UUID d = owner.get(nb);
                    if (d != null) {
                        adjOwners.add(d);
                    }
                }
                for (UUID d : adjOwners) {
                    claimableByDa.computeIfAbsent(d, k -> new ArrayList<>()).add(h);
                }
            }
            if (claimableByDa.isEmpty()) {
                break;   // remaining unclaimed hexes have no live neighbor → orphans
            }
            // Grow the globally least-loaded eligible DA (ties by id) — spreads load across neighbors.
            UUID grower = claimableByDa.keySet().stream()
                    .min(Comparator.comparingInt((UUID d) -> load.getOrDefault(d, 0)).thenComparing(UUID::toString))
                    .orElseThrow();
            // It claims the adjacent hex it shares the most border with (ties by hex id, for determinism).
            UUID hex = pickStrongestBorder(claimableByDa.get(grower), grower, adjacency, owner);

            owner.put(hex, grower);
            unclaimed.remove(hex);
            load.merge(grower, 1, Integer::sum);
            reassignments.add(new HexReassignment(hex, absentOwner.get(hex), grower));
        }

        List<UUID> orphans = new ArrayList<>(unclaimed);
        List<UUID> sortedAbsent = absentDaIds.stream().sorted().toList();
        log.info("Absence plan cityId={} absent={} → {} hexes reassigned, {} orphans",
                cityId, sortedAbsent.size(), reassignments.size(), orphans.size());
        return new AbsenceReassignmentPlan(cityId, date, sortedAbsent, reassignments, orphans);
    }

    /** The candidate hex sharing the most border with {@code da}; ties broken by hex id (deterministic). */
    private UUID pickStrongestBorder(List<UUID> candidates, UUID da,
                                     Map<UUID, List<UUID>> adjacency, Map<UUID, UUID> owner) {
        UUID best = null;
        int bestBorder = -1;
        for (UUID h : candidates) {
            int border = 0;
            for (UUID nb : adjacency.getOrDefault(h, List.of())) {
                if (da.equals(owner.get(nb))) {
                    border++;
                }
            }
            if (border > bestBorder || (border == bestBorder && (best == null || h.compareTo(best) < 0))) {
                bestBorder = border;
                best = h;
            }
        }
        return best;
    }

    /**
     * Recompute the split and commit it as one append-only {@code INTRADAY_OVERRIDE} proposal:
     * receivers get their full new hex-set (existing + gained), absent DAs are fully vacated, and the
     * affected DAs' standing APPROVED rows are superseded. The absent DAs are retired even when no hex
     * finds a receiver (their orphaned hexes are simply left uncovered for escalation). Returns the
     * applied plan so M5 can move the matching tasks.
     */
    @Transactional
    AbsenceReassignmentPlan apply(UUID cityId, List<UUID> absentDaIds, LocalDate date, UUID reviewerId) {
        AbsenceReassignmentPlan plan = plan(cityId, absentDaIds, date);
        Set<UUID> absent = new HashSet<>(absentDaIds);
        Instant now = Instant.now();

        // Always retire the absent DAs — they're absent whether or not any of their hexes found a
        // receiver. Hexes with no live neighbor (orphans) are left uncovered for manual escalation.
        for (UUID da : absent) {
            supersedeApproved(da, date);
        }
        if (plan.reassignments().isEmpty()) {
            log.info("Absence reassignment: retired {} absent DA(s), no receiver for any hex ({} orphans)",
                    absent.size(), plan.orphanHexIds().size());
            return plan;
        }

        Map<UUID, List<UUID>> gainedByReceiver = plan.reassignments().stream()
                .collect(Collectors.groupingBy(HexReassignment::toDaId,
                        Collectors.mapping(HexReassignment::hexId, Collectors.toList())));
        Set<UUID> receivers = gainedByReceiver.keySet();

        int covered = plan.reassignments().size();
        int total = covered + plan.orphanHexIds().size();
        double coveragePct = total == 0 ? 100.0 : (100.0 * covered / total);
        // Record the orphan (uncovered) hexes on the proposal so reports/UI don't read "[]" as full coverage.
        String understaffed = plan.orphanHexIds().isEmpty() ? "[]"
                : plan.orphanHexIds().stream().map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(",", "[", "]"));

        AssignmentProposal proposal = proposalRepository.save(AssignmentProposal.builder()
                .cityId(cityId)
                .validForDate(date)
                .status(ProposalStatus.PROPOSED)
                .proposalType(ProposalType.INTRADAY_OVERRIDE)
                .solverType(SolverType.MANUAL)
                .adjacencySource(AdjacencySource.GEOMETRIC_FALLBACK)
                .totalDas(receivers.size())
                .coveragePct(coveragePct)
                .understaffedHexIds(understaffed)
                .build());

        // Give each receiver ONLY the gained hexes (the absent DA's hexes, superseded above); the
        // receiver keeps its retained hexes through its own untouched APPROVED rows. The (da_id, hex_id,
        // valid_date) key is unique regardless of status, so we must not blindly INSERT: a hex the
        // receiver already holds APPROVED is a no-op, and a hex it holds SUPERSEDED (from an earlier
        // same-day absence that bounced this hex away and back) is REACTIVATED in place — inserting a
        // second row for that key would be rejected and roll the whole apply back.
        List<DaHexAssignment> toSave = new ArrayList<>();
        for (UUID receiver : receivers) {
            Map<UUID, DaHexAssignment> existingByHex = assignmentRepository.findByDaIdAndValidDate(receiver, date)
                    .stream().collect(Collectors.toMap(DaHexAssignment::getHexId, a -> a, (a, b) -> a));
            for (UUID hex : new LinkedHashSet<>(gainedByReceiver.get(receiver))) {
                DaHexAssignment existing = existingByHex.get(hex);
                if (existing == null) {
                    toSave.add(DaHexAssignment.builder()
                            .proposalId(proposal.getId())
                            .daId(receiver)
                            .hexId(hex)
                            .validDate(date)
                            .nDasOnHex(1)
                            .status(AssignmentStatus.APPROVED)
                            .approvedBy(reviewerId)
                            .approvedAt(now)
                            .build());
                } else if (existing.getStatus() != AssignmentStatus.APPROVED) {
                    existing.setStatus(AssignmentStatus.APPROVED);   // reactivate the slot in place
                    existing.setApprovedBy(reviewerId);
                    existing.setApprovedAt(now);
                    toSave.add(existing);
                }
                // else already APPROVED → the receiver already holds this hex, nothing to do.
            }
        }
        assignmentRepository.saveAll(toSave);

        proposal.setStatus(ProposalStatus.APPROVED);
        proposal.setReviewedBy(reviewerId);
        proposal.setReviewedAt(now);
        proposalRepository.save(proposal);

        log.info("Absence reassignment applied: proposal={} receivers={} hexes={} absent={} orphans={} coverage={}%",
                proposal.getId(), receivers.size(), covered, absent.size(), plan.orphanHexIds().size(),
                String.format("%.1f", coveragePct));
        return plan;
    }

    private List<DaHexAssignment> approvedRows(UUID daId, LocalDate date) {
        return assignmentRepository.findByDaIdAndValidDate(daId, date).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.APPROVED)
                .toList();
    }

    private void supersedeApproved(UUID daId, LocalDate date) {
        List<DaHexAssignment> rows = approvedRows(daId, date);
        rows.forEach(a -> a.setStatus(AssignmentStatus.SUPERSEDED));
        assignmentRepository.saveAll(rows);
    }

    /** H3 1-ring adjacency over the city's active hexes — hexId → neighboring hexIds. */
    private Map<UUID, List<UUID>> geometricAdjacency(List<Hex> hexes) {
        Set<Long> activeH3 = hexes.stream().map(Hex::getH3Index).collect(Collectors.toSet());
        Map<Long, UUID> h3ToId = hexes.stream().collect(Collectors.toMap(Hex::getH3Index, Hex::getId));
        Map<UUID, List<UUID>> graph = new HashMap<>();
        for (Hex hex : hexes) {
            long center = hex.getH3Index();
            List<UUID> neighbors = h3Core.gridDisk(center, 1).stream()
                    .filter(h -> h != center && activeH3.contains(h))
                    .map(h3ToId::get)
                    .toList();
            graph.put(hex.getId(), neighbors);
        }
        return graph;
    }
}
