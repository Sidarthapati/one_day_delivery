package com.oneday.grid.repository;

import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DaHexAssignmentRepository extends JpaRepository<DaHexAssignment, UUID> {

    List<DaHexAssignment> findByProposalId(UUID proposalId);

    List<DaHexAssignment> findByDaIdAndValidDate(UUID daId, LocalDate validDate);

    List<DaHexAssignment> findByHexIdAndValidDateAndStatus(UUID hexId, LocalDate validDate, AssignmentStatus status);

    List<DaHexAssignment> findByHexIdAndValidDateAndStatusIn(
            UUID hexId, LocalDate validDate, Collection<AssignmentStatus> statuses);

    List<DaHexAssignment> findByValidDateAndStatus(LocalDate validDate, AssignmentStatus status);

    List<DaHexAssignment> findByHexIdInAndValidDateAndStatus(
            Collection<UUID> hexIds, LocalDate validDate, AssignmentStatus status);

    List<DaHexAssignment> findByHexIdInAndValidDateAndStatusIn(
            Collection<UUID> hexIds, LocalDate validDate, Collection<AssignmentStatus> statuses);
}
