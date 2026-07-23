package com.oneday.sla.repository;

import com.oneday.sla.domain.SlaAction;
import com.oneday.sla.domain.SlaActionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SlaActionRepository extends JpaRepository<SlaAction, UUID> {

    List<SlaAction> findByEscalationIdOrderByCreatedAtAsc(UUID escalationId);

    boolean existsByEscalationIdAndAction(UUID escalationId, SlaActionType action);
}
