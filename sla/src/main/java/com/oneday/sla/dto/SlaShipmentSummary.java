package com.oneday.sla.dto;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.SlaShipment;

import java.time.Instant;
import java.util.UUID;

/** One control-tower row: where a parcel's SLA stands right now. */
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
        Instant deliveredAt) {

    public static SlaShipmentSummary from(SlaShipment s) {
        return new SlaShipmentSummary(
                s.getShipmentId(), s.getShipmentRef(), s.getOriginCity(), s.getDestCity(), s.getLane(),
                s.getDeliveryType(), s.getOverallState(), s.getCurrentLeg(), s.isBreached(),
                s.getBookedAt(), s.getInternalTargetAt(), s.getPublicPromiseAt(),
                s.getProjectedFinishAt(), s.getDeliveredAt());
    }
}
