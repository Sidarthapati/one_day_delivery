package com.oneday.sla.service;

import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.SlaLeg;
import com.oneday.sla.domain.SlaShipment;
import com.oneday.sla.repository.SlaLegRepository;
import com.oneday.sla.repository.SlaShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Recomputes one shipment's SLA from its legs: runs the projection, writes leg colours + the rollup,
 * upgrades to BREACHED once the internal target actually passes, and fires escalations on the
 * transition into RED / BREACHED. Called by both the event lifecycle and the sweeper.
 */
@Service
public class SlaEngine {

    private final SlaShipmentRepository shipmentRepo;
    private final SlaLegRepository legRepo;
    private final ProjectionCalculator projection;
    private final EscalationService escalation;

    public SlaEngine(SlaShipmentRepository shipmentRepo, SlaLegRepository legRepo,
                     ProjectionCalculator projection, EscalationService escalation) {
        this.shipmentRepo = shipmentRepo;
        this.legRepo = legRepo;
        this.projection = projection;
        this.escalation = escalation;
    }

    @Transactional
    public void recompute(UUID shipmentId) {
        shipmentRepo.findByShipmentId(shipmentId).ifPresent(this::recompute);
    }

    @Transactional
    public void recompute(SlaShipment ss) {
        if (ss.getClosedAt() != null) {
            return; // terminal — no further evaluation
        }
        Instant now = Instant.now();
        List<SlaLeg> legs = legRepo.findByShipmentIdOrderBySeqAsc(ss.getShipmentId());
        ProjectionCalculator.Projection p = projection.evaluate(legs, ss.getInternalTargetAt(), now);
        legRepo.saveAll(legs);

        SlaState previous = ss.getOverallState();
        SlaLegType currentLeg = legs.stream()
                .filter(l -> l.getCompletedAt() == null)
                .map(SlaLeg::getLeg)
                .findFirst()
                .orElse(null);
        ss.setCurrentLeg(currentLeg);
        ss.setProjectedFinishAt(p.projectedFinishAt());

        SlaState overall = p.overall();
        boolean breached = now.isAfter(ss.getInternalTargetAt());
        if (breached) {
            overall = SlaState.BREACHED;
            ss.setBreached(true);
        }
        ss.setOverallState(overall);
        shipmentRepo.save(ss);

        // Escalate on entering RED / BREACHED (idempotent downstream).
        if (overall == SlaState.BREACHED && previous != SlaState.BREACHED) {
            escalation.raiseBreach(ss, currentLeg, previous, "INTERNAL_TARGET_PASSED");
        } else if (overall == SlaState.RED && previous != SlaState.RED && previous != SlaState.BREACHED) {
            escalation.raiseRed(ss, currentLeg, previous);
        }
    }
}
