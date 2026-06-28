package com.oneday.hub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M7 hub configuration (design §14.3 knobs). Engine-wide defaults; per-(origin,dest,airline)
 * overrides for the underweight threshold are a later refinement (Q1). Sensible defaults so no
 * yaml is required to boot.
 */
@Component
@ConfigurationProperties(prefix = "hub")
@Data
public class HubProperties {

    /** A flight bag under this weight near cutoff is a reschedule candidate (§9, Q1). PR #3 uses it. */
    private int underweightThresholdGrams = 50_000;

    /** Minutes before flight cutoff at which a bag is auto-considered for seal/reschedule (§7.4). */
    private int bagCutoffBufferMinutes = 30;

    /** Stand occupancy %% that trips the overload high-water mark (§11). PR #3 uses it. */
    private int standHighWaterPct = 90;

    /** Dest-hub sort+stage+drop budget used in the §9 reschedule maths (the Q2 split placeholder). */
    private int destHubTailMinutes = 120;

    /** Default flight-bag stand capacity when a stand row does not override it. */
    private int defaultStandCapacity = 200;
}
