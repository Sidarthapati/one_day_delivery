package com.oneday.sla.dto;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.common.port.ShipmentContactPort;
import com.oneday.sla.domain.PriorityBand;
import com.oneday.sla.domain.SlaShipment;

import java.time.Instant;
import java.util.UUID;

/** One control-tower row: where a parcel's SLA stands right now, its triage priority, and who to call. */
public record SlaShipmentSummary(
        UUID shipmentId,
        String shipmentRef,
        // Parent order back-ref (null for legacy/pre-order rows) — lets the tower group order siblings.
        String orderRef,
        String originCity,
        String destCity,
        String lane,
        DeliveryType deliveryType,
        SlaState overallState,
        SlaLegType currentLeg,
        boolean breached,
        Instant bookedAt,
        Instant internalTargetAt,
        Instant publicPromiseAt,
        Instant projectedFinishAt,
        Instant deliveredAt,
        // Triage priority (PriorityScorer)
        PriorityBand band,
        Integer urgencyMinutes,
        Instant actByAt,
        Instant enteredStateAt,
        // Who to call — the current handler (DA today; van/hub/GHA to come) + the receiving customer.
        String handlerName,
        String handlerPhone,
        String handlerRole,
        String receiverName,
        String receiverPhone,
        // True when the parcel is on a ground leg heading into a city with adverse weather right now.
        boolean weatherExposed,
        // Anti-fatigue: a manager is on it (acknowledged, cooldown live, not since re-escalated) + who.
        boolean beingHandled,
        String acknowledgedBy) {

    /** The resolved current handler for a parcel — DA, hub desk, or GHA desk — normalised to strings. */
    public record Handler(String name, String phone, String role) {}

    /** Entity-only mapping — contact + weather null/false. Used where per-row enrichment isn't warranted. */
    public static SlaShipmentSummary from(SlaShipment s) {
        return from(s, null, null, false);
    }

    /** Full row including the resolved current handler, receiving customer, and weather exposure. */
    public static SlaShipmentSummary from(SlaShipment s,
                                          Handler handler,
                                          ShipmentContactPort.ShipmentContact contact,
                                          boolean weatherExposed) {
        return new SlaShipmentSummary(
                s.getShipmentId(), s.getShipmentRef(), s.getOrderRef(),
                s.getOriginCity(), s.getDestCity(), s.getLane(),
                s.getDeliveryType(), s.getOverallState(), s.getCurrentLeg(), s.isBreached(),
                s.getBookedAt(), s.getInternalTargetAt(), s.getPublicPromiseAt(),
                s.getProjectedFinishAt(), s.getDeliveredAt(),
                s.getBand(), s.getUrgencyMinutes(), s.getActByAt(), s.getEnteredStateAt(),
                handler == null ? null : handler.name(),
                handler == null ? null : handler.phone(),
                handler == null ? null : handler.role(),
                contact == null ? null : contact.receiverName(),
                contact == null ? null : contact.receiverPhone(),
                weatherExposed,
                com.oneday.sla.service.PriorityScorer.ackIsLive(s, Instant.now()),
                s.getAcknowledgedBy());
    }
}
