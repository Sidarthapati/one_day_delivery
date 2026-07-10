package com.oneday.hub.repository;

import com.oneday.hub.domain.StandReassignmentAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StandReassignmentAuditRepository extends JpaRepository<StandReassignmentAudit, UUID> {

    List<StandReassignmentAudit> findByBagId(UUID bagId);
}
