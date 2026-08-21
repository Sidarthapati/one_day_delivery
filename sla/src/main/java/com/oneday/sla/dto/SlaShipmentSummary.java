package com.oneday.sla.dto;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.common.port.CourierOnShipmentPort;
import com.oneday.common.port.ShipmentContactPort;
import com.oneday.sla.domain.PriorityBand;
import com.oneday.sla.domain.SlaShipment;

import java.time.Instant;
import java.util.UUID;

/** One control-tower row: where a parcel's SLA stands right now, its triage priority, and who to call. */
public record SlaShipmentSummary(
        UUID shipmentId,
        String shipmentRef,
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
        String receiverPhone) {

    /** Entity-only mapping — contact fields null. Used where a per-row contact lookup isn't warranted. */
    public static SlaShipmentSummary from(SlaShipment s) {
        return from(s, null, null);
    }

    /** Full row including the resolved current handler and receiving customer. */
    public static SlaShipmentSummary from(SlaShipment s,
                                          CourierOnShipmentPort.Courier handler,
                                          ShipmentContactPort.ShipmentContact contact) {
        return new SlaShipmentSummary(
                s.getShipmentId(), s.getShipmentRef(), s.getOriginCity(), s.getDestCity(), s.getLane(),
                s.getDeliveryType(), s.getOverallState(), s.getCurrentLeg(), s.isBreached(),
                s.getBookedAt(), s.getInternalTargetAt(), s.getPublicPromiseAt(),
                s.getProjectedFinishAt(), s.getDeliveredAt(),
                s.getBand(), s.getUrgencyMinutes(), s.getActByAt(), s.getEnteredStateAt(),
                handler == null ? null : handler.name(),
                handler == null ? null : handler.phone(),
                handler == null ? null : handler.role().name(),
                contact == null ? null : contact.receiverName(),
                contact == null ? null : contact.receiverPhone());
    }
}
