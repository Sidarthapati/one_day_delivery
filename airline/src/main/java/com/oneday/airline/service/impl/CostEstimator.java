package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.domain.LaneRateCard;
import org.springframework.stereotype.Component;

/**
 * GCR-style slab cost math (§10): a per-kg rate that steps down as chargeable weight crosses a
 * break, plus a fixed terminal handling fee, floored at the lane's minimum charge. An overnight
 * flight's total gets the configured discount applied before the floor, so a cheapest-first pick
 * naturally prefers overnight when it's genuinely cheaper (§5).
 */
@Component
class CostEstimator {

    private final AirlineProperties properties;

    CostEstimator(AirlineProperties properties) {
        this.properties = properties;
    }

    long estimatePaise(LaneRateCard rateCard, int weightGrams, boolean overnight) {
        double weightKg = weightGrams / 1000.0;
        long perKgPaise = ratePerKg(rateCard, weightKg);
        long total = Math.round(weightKg * perKgPaise) + rateCard.getTerminalHandlingPaise();
        if (overnight) {
            total -= total * properties.getOvernightDiscountBps() / 10_000;
        }
        return Math.max(total, rateCard.getMinChargePaise());
    }

    private long ratePerKg(LaneRateCard c, double weightKg) {
        if (weightKg >= 1000) return c.getRateQ1000PaisePerKg();
        if (weightKg >= 500) return c.getRateQ500PaisePerKg();
        if (weightKg >= 300) return c.getRateQ300PaisePerKg();
        if (weightKg >= 100) return c.getRateQ100PaisePerKg();
        if (weightKg >= 45) return c.getRateQ45PaisePerKg();
        return c.getRateBelow45kgPaisePerKg();
    }
}
