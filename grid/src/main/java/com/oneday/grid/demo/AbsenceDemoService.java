package com.oneday.grid.demo;

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
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.service.GridService;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Demo-only harness for the midday-absence flood-fill (not compiled into prod — {@code !prod}). Seeds
 * a city's grid with N contiguous synthetic DA territories, exposes the current split + hex geometry
 * for a map, and runs the real {@link GridService#planAbsenceReassignment} so the reassignment can be
 * watched live as the absent set changes.
 */
@Service
@Profile("!prod")
public class AbsenceDemoService {

    private static final Logger log = LoggerFactory.getLogger(AbsenceDemoService.class);

    private final HexRepository hexRepository;
    private final GridRepository gridRepository;
    private final DaHexAssignmentRepository assignmentRepository;
    private final AssignmentProposalRepository proposalRepository;
    private final GridService gridService;
    private final H3Core h3Core;

    public AbsenceDemoService(HexRepository hexRepository,
                             GridRepository gridRepository,
                             DaHexAssignmentRepository assignmentRepository,
                             AssignmentProposalRepository proposalRepository,
                             GridService gridService,
                             H3Core h3Core) {
        this.hexRepository = hexRepository;
        this.gridRepository = gridRepository;
        this.assignmentRepository = assignmentRepository;
        this.proposalRepository = proposalRepository;
        this.gridService = gridService;
        this.h3Core = h3Core;
    }

    // ── response shapes ───────────────────────────────────────────────────────────────────────────

    public record DemoDa(UUID daId, String label, int hexCount) {}

    public record DemoHex(UUID hexId, long h3Index, UUID ownerDaId, List<double[]> boundary) {}

    public record DemoState(UUID cityId, String cityCode, LocalDate date,
                            List<DemoDa> das, List<DemoHex> hexes) {}

    public record DemoReassignment(UUID hexId, UUID fromDaId, UUID toDaId) {}

    public record DemoPlan(List<DemoReassignment> reassignments, List<UUID> orphanHexIds,
                           List<DemoDa> receiverLoads) {}

    // ── seed ──────────────────────────────────────────────────────────────────────────────────────

    /** Partition the city's active grid into {@code daCount} contiguous synthetic DA territories for today. */
    @Transactional
    public List<DemoDa> seed(UUID cityId, int daCount) {
        LocalDate date = LocalDate.now();
        Grid grid = gridRepository.findByCityId(cityId)
                .orElseThrow(() -> new IllegalArgumentException("No grid for city " + cityId));
        List<Hex> hexes = hexRepository.findByH3GridIdAndActiveTrue(grid.getId());
        if (hexes.isEmpty()) {
            throw new IllegalStateException("City " + cityId + " has no active hexes to partition");
        }
        int n = Math.max(1, Math.min(daCount, hexes.size()));
        Map<UUID, List<UUID>> adjacency = geometricAdjacency(hexes);
        Map<UUID, Integer> region = partition(hexes, adjacency, n);

        // Fresh synthetic DAs for this seed.
        List<UUID> daIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            daIds.add(UUID.randomUUID());
        }

        // Reset any standing plan for today, then write the new APPROVED territories.
        Set<UUID> cityHexIds = hexes.stream().map(Hex::getId).collect(Collectors.toSet());
        assignmentRepository.findByValidDateAndStatus(date, AssignmentStatus.APPROVED).stream()
                .filter(a -> cityHexIds.contains(a.getHexId()))
                .forEach(a -> a.setStatus(AssignmentStatus.SUPERSEDED));

        AssignmentProposal proposal = proposalRepository.save(AssignmentProposal.builder()
                .cityId(cityId).validForDate(date)
                .status(ProposalStatus.APPROVED).proposalType(ProposalType.NIGHTLY)
                .solverType(SolverType.MANUAL).adjacencySource(AdjacencySource.GEOMETRIC_FALLBACK)
                .totalDas(n).coveragePct(100.0).understaffedHexIds("[]").build());
        Instant now = Instant.now();
        List<DaHexAssignment> rows = new ArrayList<>();
        for (Hex hex : hexes) {
            UUID da = daIds.get(region.get(hex.getId()));
            rows.add(DaHexAssignment.builder()
                    .proposalId(proposal.getId()).daId(da).hexId(hex.getId()).validDate(date)
                    .nDasOnHex(1).status(AssignmentStatus.APPROVED)
                    .approvedBy(null).approvedAt(now).build());
        }
        assignmentRepository.saveAll(rows);

        Map<Integer, Long> counts = region.values().stream()
                .collect(Collectors.groupingBy(i -> i, Collectors.counting()));
        List<DemoDa> das = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            das.add(new DemoDa(daIds.get(i), "DA " + (i + 1), counts.getOrDefault(i, 0L).intValue()));
        }
        log.info("Absence demo seeded {} DAs over {} hexes in city {}", n, hexes.size(), cityId);
        return das;
    }

    // ── current state (for the map) ────────────────────────────────────────────────────────────────

    public DemoState state(UUID cityId) {
        LocalDate date = LocalDate.now();
        Grid grid = gridRepository.findByCityId(cityId)
                .orElseThrow(() -> new IllegalArgumentException("No grid for city " + cityId));
        List<Hex> hexes = hexRepository.findByH3GridIdAndActiveTrue(grid.getId());
        Set<UUID> cityHexIds = hexes.stream().map(Hex::getId).collect(Collectors.toSet());

        Map<UUID, UUID> ownerByHex = new HashMap<>();
        Map<UUID, Integer> countByDa = new HashMap<>();
        List<DaHexAssignment> approved = assignmentRepository
                .findByValidDateAndStatus(date, AssignmentStatus.APPROVED).stream()
                .filter(a -> cityHexIds.contains(a.getHexId())).toList();
        for (DaHexAssignment a : approved) {
            ownerByHex.putIfAbsent(a.getHexId(), a.getDaId());
            countByDa.merge(a.getDaId(), 1, Integer::sum);
        }

        List<DemoHex> demoHexes = hexes.stream()
                .map(h -> new DemoHex(h.getId(), h.getH3Index(), ownerByHex.get(h.getId()),
                        boundary(h.getH3Index())))
                .toList();
        List<DemoDa> das = countByDa.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .map(e -> new DemoDa(e.getKey(), null, e.getValue()))
                .toList();

        String cityCode = gridService.resolveCityCode(cityId);
        return new DemoState(cityId, cityCode, date, das, demoHexes);
    }

    // ── plan (the real algorithm) ──────────────────────────────────────────────────────────────────

    public DemoPlan plan(UUID cityId, List<UUID> absentDaIds) {
        AbsenceReassignmentPlan plan = gridService.planAbsenceReassignment(cityId, absentDaIds, LocalDate.now());
        List<DemoReassignment> moves = plan.reassignments().stream()
                .map(r -> new DemoReassignment(r.hexId(), r.fromDaId(), r.toDaId())).toList();
        Map<UUID, Long> perReceiver = plan.reassignments().stream()
                .collect(Collectors.groupingBy(AbsenceReassignmentPlan.HexReassignment::toDaId,
                        Collectors.counting()));
        List<DemoDa> loads = perReceiver.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .map(e -> new DemoDa(e.getKey(), null, e.getValue().intValue()))
                .toList();
        return new DemoPlan(moves, plan.orphanHexIds(), loads);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    /** Multi-source BFS from {@code n} spread-out seeds → {@code n} contiguous regions (hex id → region). */
    private Map<UUID, Integer> partition(List<Hex> hexes, Map<UUID, List<UUID>> adjacency, int n) {
        // Precompute planar coords (lat/lng — fine for spread) once.
        Map<UUID, double[]> coord = new HashMap<>();
        for (Hex h : hexes) {
            LatLng c = h3Core.cellToLatLng(h.getH3Index());
            coord.put(h.getId(), new double[] {c.lat, c.lng});
        }
        // Farthest-point sampling for compact, balanced Voronoi cells (so an interior DA borders MANY
        // neighbors — that's what makes the split visible). Deterministic first seed = smallest hex id.
        List<UUID> ids = hexes.stream().map(Hex::getId).sorted().toList();
        List<UUID> seeds = new ArrayList<>();
        seeds.add(ids.get(0));
        while (seeds.size() < n) {
            UUID best = null;
            double bestDist = -1;
            for (UUID id : ids) {
                if (seeds.contains(id)) {
                    continue;
                }
                double dMin = Double.MAX_VALUE;
                for (UUID s : seeds) {
                    double[] a = coord.get(id);
                    double[] b = coord.get(s);
                    double dx = a[0] - b[0];
                    double dy = a[1] - b[1];
                    dMin = Math.min(dMin, dx * dx + dy * dy);
                }
                if (dMin > bestDist) {
                    bestDist = dMin;
                    best = id;
                }
            }
            seeds.add(best);
        }
        Map<UUID, Integer> region = new HashMap<>();
        Deque<UUID> frontier = new ArrayDeque<>();
        for (int i = 0; i < seeds.size(); i++) {
            region.put(seeds.get(i), i);
            frontier.add(seeds.get(i));
        }
        // BFS outward simultaneously from every seed — each hex joins the region that reaches it first.
        while (!frontier.isEmpty()) {
            UUID cur = frontier.poll();
            int r = region.get(cur);
            for (UUID nb : adjacency.getOrDefault(cur, List.of())) {
                if (!region.containsKey(nb)) {
                    region.put(nb, r);
                    frontier.add(nb);
                }
            }
        }
        // Any hex unreached (disconnected component) → nearest seed's region by fallback 0.
        for (Hex h : hexes) {
            region.putIfAbsent(h.getId(), 0);
        }
        return region;
    }

    private Map<UUID, List<UUID>> geometricAdjacency(List<Hex> hexes) {
        Set<Long> activeH3 = hexes.stream().map(Hex::getH3Index).collect(Collectors.toSet());
        Map<Long, UUID> h3ToId = hexes.stream().collect(Collectors.toMap(Hex::getH3Index, Hex::getId));
        Map<UUID, List<UUID>> graph = new HashMap<>();
        for (Hex hex : hexes) {
            long center = hex.getH3Index();
            graph.put(hex.getId(), h3Core.gridDisk(center, 1).stream()
                    .filter(h -> h != center && activeH3.contains(h)).map(h3ToId::get).toList());
        }
        return graph;
    }

    private List<double[]> boundary(long h3Index) {
        List<double[]> ring = new ArrayList<>();
        for (LatLng p : h3Core.cellToBoundary(h3Index)) {
            ring.add(new double[] {p.lat, p.lng});
        }
        return ring;
    }
}
