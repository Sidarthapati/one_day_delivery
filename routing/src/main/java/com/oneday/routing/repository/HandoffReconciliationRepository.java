package com.oneday.routing.repository;

import com.oneday.routing.domain.HandoffReconciliation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HandoffReconciliationRepository extends JpaRepository<HandoffReconciliation, UUID> {

    List<HandoffReconciliation> findByManifestId(UUID manifestId);
}
