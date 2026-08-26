package com.oneday.grid.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The territory-split plan for one or more absent DAs (M3, midday absence). Every hex owned by an
 * absent DA is folded into exactly one <b>territory-neighbor</b> — the adjacent DA (H3 1-ring) with
 * the most spare capacity, contiguity preserved. Hexes whose every neighbor is also absent (or that
 * are isolated) have no receiver and surface as {@code orphanHexIds} for manual escalation.
 *
 * <p>Compute-only when returned from {@code planAbsenceReassignment} (advisory preview); the same
 * shape is returned from {@code applyAbsenceReassignment} after the {@code INTRADAY_OVERRIDE}
 * proposal has been written + approved, so the caller (M5) can move the matching tasks.</p>
 */
public record AbsenceReassignmentPlan(
        UUID cityId,
        LocalDate date,
        List<UUID> absentDaIds,
        List<HexReassignment> reassignments,
        List<UUID> orphanHexIds) {

    /** One absent-DA hex folded into a neighbor. */
    public record HexReassignment(UUID hexId, UUID fromDaId, UUID toDaId) {}

    /** The receiving neighbor for {@code hexId}, or {@code null} if the hex is an orphan. */
    public UUID ownerOf(UUID hexId) {
        return reassignments.stream()
                .filter(r -> r.hexId().equals(hexId))
                .map(HexReassignment::toDaId)
                .findFirst()
                .orElse(null);
    }
}
