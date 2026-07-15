package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.domain.LaneRateCard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CostEstimatorTest {

    private final AirlineProperties properties = new AirlineProperties();
    private final CostEstimator estimator = new CostEstimator(properties);

    private LaneRateCard rateCard() {
        LaneRateCard c = new LaneRateCard();
        c.setMinChargePaise(150_000);        // ₹1,500
        c.setTerminalHandlingPaise(38_000);  // ₹380
        c.setRateBelow45kgPaisePerKg(6_500);
        c.setRateQ45PaisePerKg(5_800);
        c.setRateQ100PaisePerKg(5_200);
        c.setRateQ300PaisePerKg(4_700);
        c.setRateQ500PaisePerKg(4_300);
        c.setRateQ1000PaisePerKg(4_000);
        return c;
    }

    @Test
    void veryLightBag_isFlooredAtTheMinimumCharge() {
        // 1kg @ 6500 paise/kg + 38000 handling = 44500, well under the ₹1,500 minimum.
        long cost = estimator.estimatePaise(rateCard(), 1_000, false);

        assertThat(cost).isEqualTo(150_000);
    }

    @Test
    void mediumBag_usesTheMatchingWeightBreak() {
        // 100kg lands exactly on the Q100 break: 100 * 5200 + 38000 = 558000.
        long cost = estimator.estimatePaise(rateCard(), 100_000, false);

        assertThat(cost).isEqualTo(558_000);
    }

    @Test
    void heaviestBreak_appliesAtOrAboveOneTonne() {
        // 1000kg @ Q1000 rate: 1000 * 4000 + 38000 = 4038000.
        long cost = estimator.estimatePaise(rateCard(), 1_000_000, false);

        assertThat(cost).isEqualTo(4_038_000);
    }

    @Test
    void overnightFlight_getsTheConfiguredDiscountOffTheTotal() {
        long dayCost = estimator.estimatePaise(rateCard(), 100_000, false);
        long nightCost = estimator.estimatePaise(rateCard(), 100_000, true);

        // Default overnightDiscountBps = 1000 (10%).
        assertThat(nightCost).isEqualTo(dayCost - dayCost / 10);
        assertThat(nightCost).isLessThan(dayCost);
    }
}
