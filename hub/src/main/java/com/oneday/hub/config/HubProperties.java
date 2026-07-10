package com.oneday.hub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M7 hub configuration (design §14.3 knobs). Engine-wide defaults so no yaml is required to boot.
 * Reschedule is M9-decided (M7-D-006), so there are no underweight/next-flight knobs here — M7 only
 * executes FLIGHT_REASSIGNED.
 */
@Component
@ConfigurationProperties(prefix = "hub")
@Data
public class HubProperties {

    /**
     * How long before a flight departs its bag must leave the hub (and thus its cutoff). A flight at
     * T ⇒ bag cutoff at T − this (covers the airport transfer + GHA handover + loading). Default 5h.
     */
    private int hubDepartureLeadMinutes = 300;

    /** Minutes before bag cutoff at which a bag is auto-considered for seal (§7.4). */
    private int bagCutoffBufferMinutes = 30;

    /** Stand occupancy %% that trips the overload high-water mark (§11). */
    private int standHighWaterPct = 90;

    /** Default flight-bag stand capacity when a stand row does not override it. */
    private int defaultStandCapacity = 200;
}
