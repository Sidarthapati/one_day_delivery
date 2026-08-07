package com.oneday.grid.repository;

import com.oneday.common.domain.Shift;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.ProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentProposalRepository extends JpaRepository<AssignmentProposal, UUID> {

    List<AssignmentProposal> findByCityIdAndValidForDate(UUID cityId, LocalDate validForDate);

    // Most-recent-first — used by station manager UI to list proposals for review.
    List<AssignmentProposal> findByCityIdAndStatusOrderByProposedAtDesc(UUID cityId, ProposalStatus status);

    // Convenience for auto-fallback: check if any approved proposal exists for today.
    Optional<AssignmentProposal> findByCityIdAndValidForDateAndStatus(UUID cityId, LocalDate validForDate, ProposalStatus status);

    // Shift-scoped variant — the nightly plan/escalation/fallback run once per shift.
    Optional<AssignmentProposal> findByCityIdAndValidForDateAndShiftAndStatus(
            UUID cityId, LocalDate validForDate, Shift shift, ProposalStatus status);
}
