package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DaAssignmentAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DaAssignmentAuditRepository extends JpaRepository<DaAssignmentAudit, UUID> {

    /** Full decision history for a shipment (audit / support). */
    List<DaAssignmentAudit> findByShipmentId(UUID shipmentId);
}
