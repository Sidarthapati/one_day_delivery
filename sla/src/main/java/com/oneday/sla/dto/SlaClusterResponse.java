package com.oneday.sla.dto;

import com.oneday.sla.domain.PriorityBand;

import java.time.Instant;
import java.util.List;

/**
 * The at-risk queue collapsed to shared root causes — one cluster = one decision, one desk to call.
 * At 1000/day the failures correlate (one late flight puts a dozen parcels RED); ranking the causes
 * instead of the individual cards turns ~200 rows into ~10 calls. Clusters are ordered worst-band
 * first (never a band-jump), then by size (12 affected beats 1), then soonest act-by.
 */
public record SlaClusterResponse(List<Cluster> clusters) {

    public record Cluster(
            String stage,                       // PICKUP | HUB | AIR | DELIVERY
            String scope,                       // city code, or lane "BLR→DEL" for AIR
            String label,                       // "Delivery · BLR", "Airline (GHA) · BLR→DEL"
            PriorityBand band,                  // worst member's band
            int size,
            int breachedCount,
            Instant earliestActBy,              // soonest hard window across members
            List<String> refs,                  // member refs, worst-first (capped)
            SlaShipmentSummary.Handler contact  // the single desk to call for the whole cluster (null if unresolved)
    ) {
    }
}
