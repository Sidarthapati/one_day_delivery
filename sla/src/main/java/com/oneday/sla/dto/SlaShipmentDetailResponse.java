package com.oneday.sla.dto;

import java.util.List;

/** Full per-parcel SLA breakdown: the rollup, every leg, and the escalation history. */
public record SlaShipmentDetailResponse(
        SlaShipmentSummary shipment,
        List<SlaLegView> legs,
        List<SlaEscalationView> escalations) {
}
