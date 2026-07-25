package com.oneday.sla.config;

import com.oneday.common.domain.enums.SlaLegType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * M10 SLA configuration. The per-leg budgets (Annexure G) sum to the 16h internal target; the
 * clocks anchor at booking (M10-D-006). Lives in the <b>app</b>'s {@code application.yml} (the
 * runtime source of truth — the module yaml is shadowed on the classpath, per the grid precedent).
 *
 * <pre>{@code
 * sla:
 *   internal-target-hours: 16
 *   public-promise-hours: 24
 *   sweeper-fixed-delay-ms: 60000
 *   legs: { FIRST_MILE: 240, ORIGIN_HUB: 60, ORIGIN_AIRPORT: 180, AIR: 150,
 *           DEST_AIRPORT: 120, DEST_HUB: 60, LAST_MILE: 150 }
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "sla")
public class SlaProperties {

    /** The internal "ready-to-delivered" target — RED is projected breach of this (M10-D-005/006). */
    private int internalTargetHours = 16;

    /** The public market promise. Reference only; RED keys off the internal target. */
    private int publicPromiseHours = 24;

    /** How often the sweeper recomputes open shipments to catch silent overruns. */
    private long sweeperFixedDelayMs = 60_000;

    /** Per-leg time budget in minutes, keyed by {@link SlaLegType}. Sums to the 16h target. */
    private Map<SlaLegType, Integer> legs = new EnumMap<>(SlaLegType.class);

    public int getInternalTargetHours() { return internalTargetHours; }
    public void setInternalTargetHours(int internalTargetHours) { this.internalTargetHours = internalTargetHours; }

    public int getPublicPromiseHours() { return publicPromiseHours; }
    public void setPublicPromiseHours(int publicPromiseHours) { this.publicPromiseHours = publicPromiseHours; }

    public long getSweeperFixedDelayMs() { return sweeperFixedDelayMs; }
    public void setSweeperFixedDelayMs(long sweeperFixedDelayMs) { this.sweeperFixedDelayMs = sweeperFixedDelayMs; }

    public Map<SlaLegType, Integer> getLegs() { return legs; }
    public void setLegs(Map<SlaLegType, Integer> legs) { this.legs = legs; }
}
