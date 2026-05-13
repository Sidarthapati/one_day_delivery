package com.oneday.grid.repository;

import com.oneday.grid.domain.AssignmentProposalRegion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentProposalRegionRepository extends JpaRepository<AssignmentProposalRegion, UUID> {

    List<AssignmentProposalRegion> findByProposalId(UUID proposalId);

    Optional<AssignmentProposalRegion> findByProposalIdAndDaId(UUID proposalId, UUID daId);
}
