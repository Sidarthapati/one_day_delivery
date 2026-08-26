package com.oneday.grid.service.impl;

import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.dto.response.AbsenceReassignmentPlan;
import com.oneday.grid.dto.response.AbsenceReassignmentPlan.HexReassignment;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.HexRepository;
import com.uber.h3core.H3Core;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Exercises the balanced flood-fill split against real H3 geometry (a genuine hex disk), with the
 * repositories mocked to supply a synthetic ownership map.
 */
@ExtendWith(MockitoExtension.class)
class AbsenceReassignmentPlannerTest {

    private static final int RES = 9;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);
    private static final UUID CITY = UUID.randomUUID();
    // Fixed ids so the tie-break (by id) is deterministic across runs.
    private static final UUID RAVI = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID MEENA = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID SUNIL = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @Mock private DaHexAssignmentRepository assignmentRepository;
    @Mock private AssignmentProposalRepository proposalRepository;
    @Mock private GridRepository gridRepository;
    @Mock private HexRepository hexRepository;

    private H3Core h3;
    private AbsenceReassignmentPlanner planner;

    // h3 index → hex id, for the active grid we build per test.
    private final Map<Long, UUID> hexIdByH3 = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        h3 = H3Core.newInstance();
        planner = new AbsenceReassignmentPlanner(assignmentRepository, proposalRepository,
                gridRepository, hexRepository, h3);
        when(gridRepository.findByCityId(any())).thenReturn(java.util.Optional.of(Grid.builder().build()));
    }

    @Test
    void splitsAnAbsentBlobAcrossBothNeighborsWithNoOrphans() {
        // Ravi owns a 7-hex blob (a center + its ring); Meena seeds one flank, Sunil the other.
        long center = h3.latLngToCell(28.6139, 77.2090, RES);
        List<Long> blob = h3.gridDisk(center, 1);            // 7 cells → Ravi
        List<Long> ring2 = subtract(h3.gridDisk(center, 2), blob);

        Map<Long, UUID> owners = new HashMap<>();
        blob.forEach(h -> owners.put(h, RAVI));
        // Alternate the surrounding ring between the two live neighbors so both border the blob.
        List<Long> sortedRing = ring2.stream().sorted().toList();
        for (int i = 0; i < sortedRing.size(); i++) {
            owners.put(sortedRing.get(i), i % 2 == 0 ? MEENA : SUNIL);
        }
        stubGrid(owners);

        AbsenceReassignmentPlan plan = planner.plan(CITY, List.of(RAVI), DATE);

        assertThat(plan.orphanHexIds()).isEmpty();
        assertThat(plan.reassignments()).hasSize(7);                       // every Ravi hex re-covered
        Set<UUID> receivers = plan.reassignments().stream()
                .map(HexReassignment::toDaId).collect(Collectors.toSet());
        assertThat(receivers).containsExactlyInAnyOrder(MEENA, SUNIL);     // split across BOTH, not dumped on one
        Map<UUID, Long> perReceiver = plan.reassignments().stream()
                .collect(Collectors.groupingBy(HexReassignment::toDaId, Collectors.counting()));
        assertThat(perReceiver.get(MEENA)).isBetween(1L, 6L);             // balanced — neither takes all 7
        assertThat(perReceiver.get(SUNIL)).isBetween(1L, 6L);
        assertThat(plan.reassignments()).allSatisfy(r -> assertThat(r.fromDaId()).isEqualTo(RAVI));
    }

    @Test
    void absorbsInteriorHexesViaInwardGrowth() {
        // A bigger blob (disk radius 2 = 19 cells): its interior touches only other Ravi hexes, so a
        // border-only algorithm would orphan them. Flood-fill must reach every one.
        long center = h3.latLngToCell(19.0760, 72.8777, RES);
        List<Long> blob = h3.gridDisk(center, 2);            // 19 cells → Ravi
        List<Long> ring3 = subtract(h3.gridDisk(center, 3), blob);

        Map<Long, UUID> owners = new HashMap<>();
        blob.forEach(h -> owners.put(h, RAVI));
        List<Long> sortedRing = ring3.stream().sorted().toList();
        for (int i = 0; i < sortedRing.size(); i++) {
            owners.put(sortedRing.get(i), i % 2 == 0 ? MEENA : SUNIL);
        }
        stubGrid(owners);

        AbsenceReassignmentPlan plan = planner.plan(CITY, List.of(RAVI), DATE);

        assertThat(plan.orphanHexIds()).isEmpty();
        assertThat(plan.reassignments()).hasSize(19);        // interior included, none orphaned
    }

    @Test
    void leavesAnIsolatedPocketAsAnOrphan() {
        long center = h3.latLngToCell(13.0827, 80.2707, RES);
        List<Long> blob = h3.gridDisk(center, 1);            // Ravi blob with live neighbors
        List<Long> ring2 = subtract(h3.gridDisk(center, 2), blob);
        // A far-away cell Ravi also owns, with no active neighbor in the grid → unreachable.
        long island = h3.latLngToCell(13.20, 80.40, RES);

        Map<Long, UUID> owners = new HashMap<>();
        blob.forEach(h -> owners.put(h, RAVI));
        ring2.forEach(h -> owners.put(h, MEENA));
        owners.put(island, RAVI);
        stubGrid(owners);

        AbsenceReassignmentPlan plan = planner.plan(CITY, List.of(RAVI), DATE);

        assertThat(plan.orphanHexIds()).containsExactly(hexIdByH3.get(island));
        assertThat(plan.reassignments()).allSatisfy(r -> assertThat(r.toDaId()).isEqualTo(MEENA));
    }

    @Test
    void coversTwoAdjacentAbsentDasFromTheSurvivingNeighbor() {
        // Ravi and Meena are adjacent and BOTH absent; Sunil surrounds them and must absorb everything.
        long center = h3.latLngToCell(17.3850, 78.4867, RES);
        List<Long> inner = h3.gridDisk(center, 1);           // 7 cells split Ravi/Meena
        List<Long> ring2 = subtract(h3.gridDisk(center, 2), inner);

        Map<Long, UUID> owners = new HashMap<>();
        List<Long> sortedInner = inner.stream().sorted().toList();
        for (int i = 0; i < sortedInner.size(); i++) {
            owners.put(sortedInner.get(i), i % 2 == 0 ? RAVI : MEENA);
        }
        ring2.forEach(h -> owners.put(h, SUNIL));            // the only live neighbor
        stubGrid(owners);

        AbsenceReassignmentPlan plan = planner.plan(CITY, List.of(RAVI, MEENA), DATE);

        assertThat(plan.orphanHexIds()).isEmpty();
        assertThat(plan.reassignments()).hasSize(7);
        assertThat(plan.reassignments()).allSatisfy(r -> assertThat(r.toDaId()).isEqualTo(SUNIL));
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    /** Wire the hex list + APPROVED assignments implied by an h3→owner map into the mocked repos. */
    private void stubGrid(Map<Long, UUID> owners) {
        hexIdByH3.clear();
        List<Hex> hexes = new ArrayList<>();
        for (Long h3Index : owners.keySet()) {
            UUID id = UUID.randomUUID();
            hexIdByH3.put(h3Index, id);
            hexes.add(Hex.builder().id(id).h3Index(h3Index).active(true).build());
        }
        when(hexRepository.findByH3GridIdAndActiveTrue(any())).thenReturn(hexes);

        List<DaHexAssignment> rows = owners.entrySet().stream()
                .map(e -> DaHexAssignment.builder()
                        .daId(e.getValue())
                        .hexId(hexIdByH3.get(e.getKey()))
                        .validDate(DATE)
                        .nDasOnHex(1)
                        .status(AssignmentStatus.APPROVED)
                        .build())
                .toList();
        when(assignmentRepository.findByValidDateAndStatus(DATE, AssignmentStatus.APPROVED)).thenReturn(rows);
    }

    private static List<Long> subtract(List<Long> all, List<Long> remove) {
        Set<Long> r = Set.copyOf(remove);
        return all.stream().filter(h -> !r.contains(h)).toList();
    }
}
