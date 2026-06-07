package com.oneday.routing.repository;

import com.oneday.routing.domain.RouteOverrideAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RouteOverrideAuditRepository extends JpaRepository<RouteOverrideAudit, UUID> {

    List<RouteOverrideAudit> findByRoutePlanIdOrderByCreatedAt(UUID routePlanId);
}
