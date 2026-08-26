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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
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
    private static final UUID NIGHT_S1 = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

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

    @Test
    @SuppressWarnings("unchecked")
    void applyAppendsOnlyGainedHexesNeverRewritingAReceiversOwnHexes() {
        // Regression: the (da_id, hex_id, valid_date) uniqueness means apply must NOT re-insert a hex a
        // receiver already owns — earlier it rewrote each receiver's full set (retained + gained) and
        // collided on the receiver's existing APPROVED rows. Here Meena/Sunil already own their flanks;
        // apply must only add the absent Ravi's hexes to them, leaving the flank rows untouched.
        long center = h3.latLngToCell(28.6139, 77.2090, RES);
        List<Long> blob = h3.gridDisk(center, 1);            // 7 cells → Ravi
        List<Long> ring2 = subtract(h3.gridDisk(center, 2), blob);
        Map<Long, UUID> owners = new HashMap<>();
        blob.forEach(h -> owners.put(h, RAVI));
        List<Long> sortedRing = ring2.stream().sorted().toList();
        for (int i = 0; i < sortedRing.size(); i++) {
            owners.put(sortedRing.get(i), i % 2 == 0 ? MEENA : SUNIL);
        }
        stubGrid(owners);
        // approvedRows(da) — each DA's own existing hexes (what a receiver already holds).
        when(assignmentRepository.findByDaIdAndValidDate(any(), eq(DATE))).thenAnswer(inv -> {
            UUID da = inv.getArgument(0);
            return owners.entrySet().stream()
                    .filter(e -> e.getValue().equals(da))
                    .map(e -> DaHexAssignment.builder().daId(da).hexId(hexIdByH3.get(e.getKey()))
                            .validDate(DATE).nDasOnHex(1).status(AssignmentStatus.APPROVED).build())
                    .toList();
        });
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        planner.apply(CITY, List.of(RAVI), DATE, UUID.randomUUID());

        // Every hex Ravi owned, keyed for lookup.
        Set<UUID> raviHexes = blob.stream().map(hexIdByH3::get).collect(Collectors.toSet());
        Map<UUID, Set<UUID>> ownHexes = Map.of(
                MEENA, sortedRing.stream().filter(h -> owners.get(h) == MEENA).map(hexIdByH3::get).collect(Collectors.toSet()),
                SUNIL, sortedRing.stream().filter(h -> owners.get(h) == SUNIL).map(hexIdByH3::get).collect(Collectors.toSet()));

        ArgumentCaptor<List<DaHexAssignment>> cap = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository, atLeastOnce()).saveAll(cap.capture());
        List<DaHexAssignment> inserted = cap.getAllValues().stream()
                .flatMap(List::stream)
                .filter(a -> a.getStatus() == AssignmentStatus.APPROVED)   // the newly-added gained rows
                .toList();

        assertThat(inserted).isNotEmpty();
        // 1) Every inserted row is one of Ravi's hexes (a genuine gain) — never a hex already owned.
        assertThat(inserted).allSatisfy(a -> {
            assertThat(raviHexes).contains(a.getHexId());
            assertThat(ownHexes.get(a.getDaId())).doesNotContain(a.getHexId());
        });
        // 2) No (da_id, hex_id) duplicated among the inserts (the exact unique key that used to blow up).
        assertThat(inserted.stream().map(a -> a.getDaId() + ":" + a.getHexId()).distinct().count())
                .isEqualTo(inserted.size());
        // 3) All 7 of Ravi's hexes were covered exactly once.
        assertThat(inserted.stream().map(DaHexAssignment::getHexId).collect(Collectors.toSet())).isEqualTo(raviHexes);
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyReactivatesAPriorSupersededRowInsteadOfInsertingADuplicate() {
        // Sequential same-day absence can bounce a hex back to a DA that already has a SUPERSEDED row for
        // it. The (da_id, hex_id, valid_date) key is unique across statuses, so apply must REACTIVATE that
        // row, not insert a second one (which the DB rejects, rolling the whole apply back).
        long center = h3.latLngToCell(28.6139, 77.2090, RES);
        List<Long> blob = h3.gridDisk(center, 1);            // 7 → Ravi
        List<Long> ring2 = subtract(h3.gridDisk(center, 2), blob);
        Map<Long, UUID> owners = new HashMap<>();
        blob.forEach(h -> owners.put(h, RAVI));
        ring2.forEach(h -> owners.put(h, MEENA));            // Meena surrounds Ravi → gains all 7 blob hexes
        stubGrid(owners);

        UUID bouncedHex = hexIdByH3.get(blob.get(0));        // a Ravi hex Meena will regain
        DaHexAssignment supersededBounce = DaHexAssignment.builder()
                .daId(MEENA).hexId(bouncedHex).validDate(DATE).nDasOnHex(1)
                .status(AssignmentStatus.SUPERSEDED).build();
        when(assignmentRepository.findByDaIdAndValidDate(any(), eq(DATE))).thenAnswer(inv -> {
            UUID da = inv.getArgument(0);
            List<DaHexAssignment> rows = owners.entrySet().stream()
                    .filter(e -> e.getValue().equals(da))
                    .map(e -> DaHexAssignment.builder().daId(da).hexId(hexIdByH3.get(e.getKey()))
                            .validDate(DATE).nDasOnHex(1).status(AssignmentStatus.APPROVED).build())
                    .collect(Collectors.toList());
            if (da.equals(MEENA)) rows.add(supersededBounce);   // Meena already has a SUPERSEDED slot for it
            return rows;
        });
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        planner.apply(CITY, List.of(RAVI), DATE, UUID.randomUUID());

        // The prior SUPERSEDED row was reactivated in place — same instance, now APPROVED.
        assertThat(supersededBounce.getStatus()).isEqualTo(AssignmentStatus.APPROVED);
        ArgumentCaptor<List<DaHexAssignment>> cap = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository, atLeastOnce()).saveAll(cap.capture());
        List<DaHexAssignment> saved = cap.getAllValues().stream().flatMap(List::stream).toList();
        // Exactly one row for (MEENA, bouncedHex), and it is the reactivated instance (no duplicate insert).
        assertThat(saved.stream().filter(a -> MEENA.equals(a.getDaId()) && bouncedHex.equals(a.getHexId())).count())
                .isEqualTo(1);
        assertThat(saved).anyMatch(a -> a == supersededBounce);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    @Test
    void scopesTheSplitToTheAbsentDasShiftIgnoringTheOtherShiftsSameDatePlan() {
        // A day carries two APPROVED plans on the same valid_date: SHIFT_2 (Ravi + Meena) and SHIFT_1
        // (Night). Ravi (S2) owns a 7-hex blob; Meena (S2) owns the full surrounding ring; Night (S1)
        // owns those very same ring cells for its own shift. A SHIFT_2 absence must land entirely on the
        // SHIFT_2 neighbor (Meena) and never hand territory to the off-shift Night.
        long center = h3.latLngToCell(28.6139, 77.2090, RES);
        List<Long> blob = h3.gridDisk(center, 1);
        List<Long> ring2 = subtract(h3.gridDisk(center, 2), blob);

        hexIdByH3.clear();
        List<Hex> hexes = new ArrayList<>();
        for (Long h : blob) { registerHex(h, hexes); }
        for (Long h : ring2) { registerHex(h, hexes); }
        when(hexRepository.findByH3GridIdAndActiveTrue(any())).thenReturn(hexes);

        List<DaHexAssignment> rows = new ArrayList<>();
        blob.forEach(h -> rows.add(approvedRow(RAVI, h)));
        ring2.forEach(h -> rows.add(approvedRow(MEENA, h)));      // SHIFT_2 ring
        ring2.forEach(h -> rows.add(approvedRow(NIGHT_S1, h)));   // SHIFT_1 ring — same cells, other shift
        when(assignmentRepository.findByValidDateAndStatus(DATE, AssignmentStatus.APPROVED)).thenReturn(rows);

        // Scope = the SHIFT_2 roster only (Ravi + Meena); Night is on SHIFT_1.
        AbsenceReassignmentPlan plan = planner.plan(CITY, List.of(RAVI), DATE, Set.of(RAVI, MEENA));

        assertThat(plan.orphanHexIds()).isEmpty();
        assertThat(plan.reassignments()).hasSize(7);
        Set<UUID> receivers = plan.reassignments().stream()
                .map(HexReassignment::toDaId).collect(Collectors.toSet());
        assertThat(receivers).containsExactly(MEENA);            // the SHIFT_1 DA never receives
        assertThat(receivers).doesNotContain(NIGHT_S1);
    }

    private void registerHex(long h3Index, List<Hex> into) {
        UUID id = UUID.randomUUID();
        hexIdByH3.put(h3Index, id);
        into.add(Hex.builder().id(id).h3Index(h3Index).active(true).build());
    }

    private DaHexAssignment approvedRow(UUID da, long h3Index) {
        return DaHexAssignment.builder()
                .daId(da).hexId(hexIdByH3.get(h3Index))
                .validDate(DATE).nDasOnHex(1).status(AssignmentStatus.APPROVED).build();
    }

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
