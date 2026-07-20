package com.oneday.sla.service;

import com.oneday.common.domain.enums.EscalationLevel;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.common.kafka.events.sla.SlaBreachedEvent;
import com.oneday.common.kafka.events.sla.SlaEscalationRaisedEvent;
import com.oneday.sla.domain.SlaEscalation;
import com.oneday.sla.domain.SlaShipment;
import com.oneday.sla.events.SlaEventProducer;
import com.oneday.sla.repository.SlaEscalationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Raises escalations when a shipment enters RED / BREACHED. Each raise is an append-only
 * {@code sla_escalation} row plus an event on {@code oneday.sla.events} (the seam the notification
 * service / M11 consume). Idempotent per (shipment, leg, colour) so re-evaluation never double-fires.
 *
 * <p>Level policy (PRD RACI): RED → Station Manager (Supervisor) of the parcel's custody city;
 * a confirmed breach → Admin / control tower. The notification service resolves the on-duty person.</p>
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final SlaEscalationRepository escalationRepo;
    private final SlaEventProducer producer;

    public EscalationService(SlaEscalationRepository escalationRepo, SlaEventProducer producer) {
        this.escalationRepo = escalationRepo;
        this.producer = producer;
    }

    /** A leg went RED — notify the city's station manager. Idempotent. */
    public void raiseRed(SlaShipment ss, SlaLegType leg, SlaState from) {
        raise(ss, leg, from, SlaState.RED, EscalationLevel.STATION_MANAGER, "PROJECTED_BREACH");
    }

    /** The SLA is confirmed breached (target passed or hard failure) — raise to Admin + emit breach. */
    public void raiseBreach(SlaShipment ss, SlaLegType leg, SlaState from, String reasonCode) {
        boolean raised = raise(ss, leg, from, SlaState.BREACHED, EscalationLevel.ADMIN, reasonCode);
        if (raised) {
            producer.breached(new SlaBreachedEvent(
                    ss.getShipmentId(), ss.getShipmentRef(), leg, cityFor(ss, leg),
                    reasonCode, ss.getInternalTargetAt(), Instant.now()));
        }
    }

    private boolean raise(SlaShipment ss, SlaLegType leg, SlaState from,
                          SlaState to, EscalationLevel level, String reasonCode) {
        if (escalationRepo.existsByShipmentIdAndLegAndToState(ss.getShipmentId(), leg, to)) {
            return false;
        }
        String city = cityFor(ss, leg);
        SlaEscalation esc = new SlaEscalation();
        esc.setShipmentId(ss.getShipmentId());
        esc.setShipmentRef(ss.getShipmentRef());
        esc.setLeg(leg);
        esc.setFromState(from);
        esc.setToState(to);
        esc.setLevel(level);
        esc.setCity(city);
        esc.setReasonCode(reasonCode);
        esc.setProjectedFinishAt(ss.getProjectedFinishAt());
        esc = escalationRepo.save(esc);

        producer.escalationRaised(new SlaEscalationRaisedEvent(
                esc.getId(), ss.getShipmentId(), ss.getShipmentRef(), leg, to, level, city,
                reasonCode, ss.getProjectedFinishAt(), ss.getInternalTargetAt(), Instant.now()));

        log.warn("SLA {} escalated to {} for shipment {} leg {} (projected {}, target {})",
                to, level, ss.getShipmentRef(), leg, ss.getProjectedFinishAt(), ss.getInternalTargetAt());
        return true;
    }

    /** Custody city: destination side once the parcel is on the dest legs, origin otherwise. */
    private String cityFor(SlaShipment ss, SlaLegType leg) {
        if (leg == SlaLegType.DEST_AIRPORT || leg == SlaLegType.DEST_HUB || leg == SlaLegType.LAST_MILE) {
            return ss.getDestCity();
        }
        return ss.getOriginCity();
    }

    UUID lastEscalationId(UUID shipmentId) {
        return escalationRepo.findFirstByShipmentIdOrderByCreatedAtDesc(shipmentId)
                .map(SlaEscalation::getId).orElse(null);
    }
}
