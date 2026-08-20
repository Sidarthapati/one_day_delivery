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
    private final PriorityScorer priorityScorer;

    public SlaEngine(SlaShipmentRepository shipmentRepo, SlaLegRepository legRepo,
                     ProjectionCalculator projection, EscalationService escalation,
                     PriorityScorer priorityScorer) {
        this.shipmentRepo = shipmentRepo;
        this.legRepo = legRepo;
        this.projection = projection;
        this.escalation = escalation;
        this.priorityScorer = priorityScorer;
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

        // Clock not started yet (pickup not completed) — no target, no running legs, so nothing to
        // colour or breach. Stay GREEN and just track the current leg.
        if (ss.getInternalTargetAt() == null) {
            SlaLegType pendingLeg = legs.stream()
                    .filter(l -> l.getCompletedAt() == null)
                    .map(SlaLeg::getLeg)
                    .findFirst()
                    .orElse(null);
            ss.setCurrentLeg(pendingLeg);
            applyState(ss, SlaState.GREEN, now);
            applyPriority(ss, legs, now);
            shipmentRepo.save(ss);
            return;
        }

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
        applyState(ss, overall, now);
        applyPriority(ss, legs, now);
        shipmentRepo.save(ss);

        // Escalate on entering RED / BREACHED (idempotent downstream).
        if (overall == SlaState.BREACHED && previous != SlaState.BREACHED) {
            escalation.raiseBreach(ss, currentLeg, previous, "INTERNAL_TARGET_PASSED");
        } else if (overall == SlaState.RED && previous != SlaState.RED && previous != SlaState.BREACHED) {
            escalation.raiseRed(ss, currentLeg, previous);
        }
    }

    /** Set the colour, stamping entered_state_at only when it actually changes (for "in RED 12m"). */
    private void applyState(SlaShipment ss, SlaState next, Instant now) {
        if (ss.getOverallState() != next || ss.getEnteredStateAt() == null) {
            ss.setEnteredStateAt(now);
        }
        ss.setOverallState(next);
    }

    /** Recompute + store the triage priority (band, act-by, urgency, score). */
    private void applyPriority(SlaShipment ss, List<SlaLeg> legs, Instant now) {
        PriorityScorer.Scored scored = priorityScorer.score(ss, legs, now);
        ss.setBand(scored.band());
        ss.setActByAt(scored.actByAt());
        ss.setUrgencyMinutes(scored.urgencyMinutes());
        ss.setPriorityScore(scored.score());
    }
}
