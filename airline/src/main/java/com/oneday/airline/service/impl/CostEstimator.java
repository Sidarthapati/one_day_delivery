package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.consolidator.ConsolidatorLaneRate;
import org.springframework.stereotype.Component;

/**
 * GCR-style slab cost math (§10): a per-kg rate that steps down as chargeable weight crosses a
 * break, plus a fixed terminal handling fee, floored at the lane's minimum charge. A <b>prime</b>
 * (overnight, 00:00–09:00) flight's total gets the configured surcharge added before the floor, so a
 * cheapest-first pick naturally <em>avoids</em> the expensive prime window unless it's the only flight
 * that meets the promise (§5).
 */
@Component
class CostEstimator {

    private final AirlineProperties properties;

    CostEstimator(AirlineProperties properties) {
        this.properties = properties;
    }

    long estimatePaise(ConsolidatorLaneRate rateCard, int weightGrams, boolean prime) {
        double weightKg = weightGrams / 1000.0;
        long perKgPaise = ratePerKg(rateCard, weightKg);
        long total = Math.round(weightKg * perKgPaise) + rateCard.terminalHandlingPaise();
        if (prime) {
            total += total * properties.getPrimeSurchargeBps() / 10_000;   // expensive prime-window rate
        }
        return Math.max(total, rateCard.minChargePaise());
    }

    private long ratePerKg(ConsolidatorLaneRate c, double weightKg) {
        if (weightKg >= 1000) return c.rateQ1000PaisePerKg();
        if (weightKg >= 500) return c.rateQ500PaisePerKg();
        if (weightKg >= 300) return c.rateQ300PaisePerKg();
        if (weightKg >= 100) return c.rateQ100PaisePerKg();
        if (weightKg >= 45) return c.rateQ45PaisePerKg();
        return c.rateBelow45kgPaisePerKg();
    }
}
