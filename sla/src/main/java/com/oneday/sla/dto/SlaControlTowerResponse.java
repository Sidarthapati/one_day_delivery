package com.oneday.sla.dto;

import java.util.List;

/** A page of the live control tower. */
public record SlaControlTowerResponse(
        int page,
        int size,
        long total,
        List<SlaShipmentSummary> items) {
}
