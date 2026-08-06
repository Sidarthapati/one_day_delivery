package com.oneday.sla.service;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.common.kafka.events.ShipmentCancelledEvent;
import com.oneday.common.kafka.events.ShipmentCreatedEvent;
import com.oneday.common.kafka.events.ShipmentStateChangedEvent;
import com.oneday.sla.config.SlaProperties;
import com.oneday.sla.domain.SlaLeg;
import com.oneday.sla.domain.SlaShipment;
import com.oneday.sla.repository.SlaLegRepository;
import com.oneday.sla.repository.SlaShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns the event stream into SLA state. The {@code ShipmentStateChanged} backbone opens/closes legs;
 * parcel-keyed enrichment (hub sort deadline, flight cutoff, loop overflow) sharpens a leg's deadline.
 * Every mutation ends in {@link SlaEngine#recompute}.
 *
 * <p>v1 attribution limit (M10-D-007, self-contained): van/flight/bag-level signals that carry no
 * parcel id can't be mapped to a shipment without a cross-module map, so they are logged, not
 * attributed. The backbone + parcel-keyed events cover the lifecycle.</p>
 */
@Service
public class SlaLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SlaLifecycleService.class);

    private final SlaShipmentRepository shipmentRepo;
    private final SlaLegRepository legRepo;
    private final SlaLegCatalog catalog;
    private final SlaProperties props;
    private final SlaEngine engine;
    private final EscalationService escalation;

    public SlaLifecycleService(SlaShipmentRepository shipmentRepo, SlaLegRepository legRepo,
                               SlaLegCatalog catalog, SlaProperties props, SlaEngine engine,
                               EscalationService escalation) {
        this.shipmentRepo = shipmentRepo;
        this.legRepo = legRepo;
        this.catalog = catalog;
        this.props = props;
        this.engine = engine;
        this.escalation = escalation;
    }

    // ── Backbone ───────────────────────────────────────────────────────────

    @Transactional
    public void onCreated(ShipmentCreatedEvent e) {
        if (e.getShipmentId() == null || shipmentRepo.findByShipmentId(e.getShipmentId()).isPresent()) {
            return; // idempotent
        }
        Instant booked = e.getOccurredAt() != null ? e.getOccurredAt() : Instant.now();
        DeliveryType dt = e.getDeliveryType() != null ? e.getDeliveryType() : DeliveryType.INTERCITY;

        SlaShipment ss = new SlaShipment();
        ss.setShipmentId(e.getShipmentId());
        ss.setShipmentRef(e.getShipmentRef());
        ss.setOriginCity(e.getOriginCity());
        ss.setDestCity(e.getDestCity());
        ss.setLane(lane(e.getOriginCity(), e.getDestCity()));
        ss.setDeliveryType(dt);
        ss.setBookedAt(booked);
        ss.setInternalTargetAt(booked.plus(Duration.ofHours(props.getInternalTargetHours())));
        ss.setPublicPromiseAt(booked.plus(Duration.ofHours(props.getPublicPromiseHours())));
        ss.setEtaPromised(e.getEtaPromised());
        ss.setOverallState(SlaState.GREEN);
        shipmentRepo.save(ss);

        List<SlaLegType> plan = catalog.plan(dt);
        for (int i = 0; i < plan.size(); i++) {
            SlaLegType lt = plan.get(i);
            SlaLeg leg = new SlaLeg();
            leg.setShipmentId(e.getShipmentId());
            leg.setLeg(lt);
            leg.setSeq(i);
            leg.setBudgetMinutes(catalog.budgetMinutes(lt));
            if (i == 0) { // first mile is live from booking (M10-D-006)
                leg.setStartedAt(booked);
                leg.setDeadlineAt(booked.plus(Duration.ofMinutes(leg.getBudgetMinutes())));
            }
            legRepo.save(leg);
        }
        engine.recompute(ss);
        log.debug("Opened SLA for {} ({} legs, target {})", e.getShipmentRef(), plan.size(), ss.getInternalTargetAt());
    }

    @Transactional
    public void onStateChanged(ShipmentStateChangedEvent e) {
        Optional<SlaShipment> opt = shipmentRepo.findByShipmentId(e.getShipmentId());
        if (opt.isEmpty()) {
            return; // no SLA opened (created event not seen) — skip in v1
        }
        SlaShipment ss = opt.get();
        if (ss.getClosedAt() != null) {
            return;
        }
        ShipmentState to = e.getToState();
        Instant at = e.getOccurredAt() != null ? e.getOccurredAt() : Instant.now();

        if (catalog.isTerminalSuccess(to)) {
            closeDelivered(ss, at);
            return;
        }
        if (to == ShipmentState.RTO_COMPLETED) {
            closeReturned(ss, at);
            return;
        }
        if (catalog.isException(to)) {
            markException(ss, to, at);
            return;
        }
        catalog.activeLeg(to).ifPresent(legType -> advanceTo(ss, legType, at));
        engine.recompute(ss);
    }

    @Transactional
    public void onCancelled(ShipmentCancelledEvent e) {
        shipmentRepo.findByShipmentId(e.getShipmentId()).ifPresent(ss -> {
            if (ss.getClosedAt() != null) {
                return;
            }
            Instant at = e.getOccurredAt() != null ? e.getOccurredAt() : Instant.now();
            ss.setClosedAt(at);
            ss.setOverallState(SlaState.CLOSED); // a cancellation is not a breach
            ss.setCurrentLeg(null);
            shipmentRepo.save(ss);
        });
    }

    // ── Enrichment (parcel-keyed; parcelId == shipmentId in v1) ──────────────

    /** Hub staged the parcel for last-mile with an authoritative deadline → sharpen LAST_MILE. */
    @Transactional
    public void enrichLastMileDeadline(UUID shipmentId, Instant slaDeadline) {
        overrideDeadline(shipmentId, SlaLegType.LAST_MILE, slaDeadline);
    }

    /** A flight was (re)assigned with a new cutoff → the parcel must clear the airport by then. */
    @Transactional
    public void enrichFlightCutoff(List<UUID> shipmentIds, Instant newCutoff) {
        if (shipmentIds == null || newCutoff == null) {
            return;
        }
        for (UUID id : shipmentIds) {
            overrideDeadline(id, SlaLegType.ORIGIN_AIRPORT, newCutoff);
        }
    }

    /** A parcel can't fit a feasible loop before its deadline → tighten the open leg + re-evaluate. */
    @Transactional
    public void enrichLoopOverflow(UUID shipmentId, Instant deadline) {
        shipmentRepo.findByShipmentId(shipmentId).ifPresent(ss -> {
            if (ss.getClosedAt() != null) {
                return;
            }
            openLeg(ss.getShipmentId()).ifPresent(leg -> {
                if (deadline != null) {
                    leg.setDeadlineAt(deadline);
                    leg.setSourceEvent("LOOP_OVERFLOW");
                    legRepo.save(leg);
                }
            });
            engine.recompute(ss);
        });
    }

    /** A parcel-scoped DA signal (pickup completed / cron missed) — just re-evaluate on fresh info. */
    @Transactional
    public void touch(UUID shipmentId) {
        if (shipmentId != null) {
            engine.recompute(shipmentId);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void advanceTo(SlaShipment ss, SlaLegType target, Instant at) {
        List<SlaLeg> legs = legRepo.findByShipmentIdOrderBySeqAsc(ss.getShipmentId());
        SlaLeg targetLeg = legs.stream().filter(l -> l.getLeg() == target).findFirst().orElse(null);
        if (targetLeg == null) {
            return; // state maps to a leg not in this shipment's plan (variant) — ignore
        }
        int k = targetLeg.getSeq();
        for (SlaLeg leg : legs) {
            if (leg.getSeq() < k) {
                if (leg.getStartedAt() == null) {
                    leg.setStartedAt(at);
                }
                if (leg.getCompletedAt() == null) {
                    leg.setCompletedAt(at);
                }
            } else if (leg.getSeq() == k && leg.getStartedAt() == null) {
                leg.setStartedAt(at);
                leg.setDeadlineAt(at.plus(Duration.ofMinutes(leg.getBudgetMinutes())));
            }
        }
        legRepo.saveAll(legs);
    }

    private void closeDelivered(SlaShipment ss, Instant at) {
        completeAll(ss, at);
        ss.setDeliveredAt(at);
        ss.setClosedAt(at);
        ss.setCurrentLeg(null);
        boolean late = at.isAfter(ss.getInternalTargetAt());
        ss.setBreached(late);
        ss.setOverallState(late ? SlaState.BREACHED : SlaState.CLOSED);
        shipmentRepo.save(ss);
        if (late) {
            escalation.raiseBreach(ss, SlaLegType.LAST_MILE, SlaState.RED, "DELIVERED_LATE");
        }
    }

    private void closeReturned(SlaShipment ss, Instant at) {
        completeAll(ss, at);
        ss.setClosedAt(at);
        ss.setCurrentLeg(null);
        ss.setBreached(true);
        ss.setOverallState(SlaState.BREACHED);
        shipmentRepo.save(ss);
    }

    private void markException(SlaShipment ss, ShipmentState to, Instant at) {
        ss.setBreached(true);
        ss.setOverallState(SlaState.BREACHED);
        shipmentRepo.save(ss);
        escalation.raiseBreach(ss, ss.getCurrentLeg(), SlaState.RED, to.name());
    }

    private void completeAll(SlaShipment ss, Instant at) {
        List<SlaLeg> legs = legRepo.findByShipmentIdOrderBySeqAsc(ss.getShipmentId());
        for (SlaLeg leg : legs) {
            if (leg.getStartedAt() == null) {
                leg.setStartedAt(at);
            }
            if (leg.getCompletedAt() == null) {
                leg.setCompletedAt(at);
            }
        }
        legRepo.saveAll(legs);
    }

    private void overrideDeadline(UUID shipmentId, SlaLegType legType, Instant deadline) {
        if (shipmentId == null || deadline == null) {
            return;
        }
        shipmentRepo.findByShipmentId(shipmentId).ifPresent(ss -> {
            if (ss.getClosedAt() != null) {
                return;
            }
            legRepo.findByShipmentIdAndLeg(shipmentId, legType).ifPresent(leg -> {
                leg.setDeadlineAt(deadline);
                leg.setSourceEvent("ENRICHED");
                legRepo.save(leg);
            });
            engine.recompute(ss);
        });
    }

    private Optional<SlaLeg> openLeg(UUID shipmentId) {
        return legRepo.findByShipmentIdOrderBySeqAsc(shipmentId).stream()
                .filter(l -> l.getStartedAt() != null && l.getCompletedAt() == null)
                .findFirst();
    }

    private static String lane(String origin, String dest) {
        if (origin == null || dest == null) {
            return null;
        }
        return origin + "-" + dest;
    }
}
