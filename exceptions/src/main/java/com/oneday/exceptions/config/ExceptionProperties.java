package com.oneday.exceptions.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M11 tuning knobs. Lives in the <b>app</b>'s {@code application.yml} (the runtime source of truth),
 * picked up by the app component-scan — same pattern as {@code SlaProperties}.
 *
 * <pre>{@code
 * exceptions:
 *   max-reattempts: 2
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "exceptions")
public class ExceptionProperties {

    /**
     * How many re-attempts a failed pickup/delivery gets before the case is flagged UNDELIVERABLE and
     * RTO is recommended (so {@code maxReattempts + 1} total attempts). ponytail: global for v1;
     * per-lane/category is a later refinement (PRD F1).
     */
    private int maxReattempts = 2;

    public int maxReattempts() {
        return maxReattempts;
    }

    public void setMaxReattempts(int maxReattempts) {
        this.maxReattempts = maxReattempts;
    }
}
