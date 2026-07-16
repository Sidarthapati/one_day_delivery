package com.oneday.sla.repository;

import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.sla.domain.SlaLeg;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlaLegRepository extends JpaRepository<SlaLeg, UUID> {

    List<SlaLeg> findByShipmentIdOrderBySeqAsc(UUID shipmentId);

    Optional<SlaLeg> findByShipmentIdAndLeg(UUID shipmentId, SlaLegType leg);
}
