package com.oneday.grid.repository;

import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaTileAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DaTileAssignmentRepository extends JpaRepository<DaTileAssignment, UUID> {

    // All assignments belonging to a proposal — used when activating/superseding.
    List<DaTileAssignment> findByProposalId(UUID proposalId);

    // DA's full tile set for a given date — used by M5 (GET /grid/assignments/da/{daId}).
    List<DaTileAssignment> findByDaIdAndValidDate(UUID daId, LocalDate validDate);

    // All DA assignments for a tile on a date with a given status — used for contiguity
    // checks and for finding which DA covers a tile at shift start.
    List<DaTileAssignment> findByTileIdAndValidDateAndStatus(UUID tileId, LocalDate validDate, AssignmentStatus status);

    // Tile's live assignments (APPROVED or ACTIVE) — used by the tile detail panel.
    List<DaTileAssignment> findByTileIdAndValidDateAndStatusIn(
            UUID tileId, LocalDate validDate, Collection<AssignmentStatus> statuses);

    // Full city assignment for a date and status — used by M5 (GET /grid/assignments).
    List<DaTileAssignment> findByValidDateAndStatus(LocalDate validDate, AssignmentStatus status);

    // City-scoped active assignments: fetch only tiles belonging to a specific city grid.
    List<DaTileAssignment> findByTileIdInAndValidDateAndStatus(
            Collection<UUID> tileIds, LocalDate validDate, AssignmentStatus status);

    // City-scoped assignments for multiple statuses (e.g. APPROVED + ACTIVE both count as "live").
    List<DaTileAssignment> findByTileIdInAndValidDateAndStatusIn(
            Collection<UUID> tileIds, LocalDate validDate, Collection<AssignmentStatus> statuses);
}
