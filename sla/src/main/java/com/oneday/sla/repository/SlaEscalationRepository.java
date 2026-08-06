package com.oneday.sla.repository;

import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.SlaEscalation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlaEscalationRepository extends JpaRepository<SlaEscalation, UUID> {

    /** Idempotency guard: has this shipment already been escalated for this leg at this colour? */
    boolean existsByShipmentIdAndLegAndToState(UUID shipmentId, SlaLegType leg, SlaState toState);

    List<SlaEscalation> findByShipmentIdOrderByCreatedAtDesc(UUID shipmentId);

    Optional<SlaEscalation> findFirstByShipmentIdOrderByCreatedAtDesc(UUID shipmentId);
}
