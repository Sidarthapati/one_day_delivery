package com.oneday.common.port.dto;

import java.util.Map;

/**
 * Internal per-parcel cost floor produced by M2's costing model. This is the marginal
 * fulfilment cost of moving one parcel through the chain (pickup DA share + van share +
 * hub + airline) at the ~70% DA-utilisation target. It is used by M5/M6 scheduling
 * feasibility checks and is <b>never</b> exposed to customers.
 *
 * @param city                  city code these figures apply to
 * @param costFloorPaise        total per-parcel cost floor
 * @param breakdown             itemised components, e.g. {"da_pickup": 1200, "van": 400, "hub": 300, "airline": 2500}
 * @param costingVersion        version of the costing params applied (audit)
 */
public record CostFloorResult(
        String city,
        long costFloorPaise,
        Map<String, Long> breakdown,
        String costingVersion
) {}
