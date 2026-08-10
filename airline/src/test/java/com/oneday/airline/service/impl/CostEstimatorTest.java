package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.consolidator.ConsolidatorLaneRate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CostEstimatorTest {

    private final AirlineProperties properties = new AirlineProperties();
    private final CostEstimator estimator = new CostEstimator(properties);

    private ConsolidatorLaneRate rateCard() {
        return new ConsolidatorLaneRate("DEL", "BOM",
                150_000,   // min charge, ₹1,500
                38_000,    // terminal handling, ₹380
                6_500, 5_800, 5_200, 4_700, 4_300, 4_000);
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
    void primeFlight_getsTheConfiguredSurchargeOnTopOfTheTotal() {
        long dayCost = estimator.estimatePaise(rateCard(), 100_000, false);
        long primeCost = estimator.estimatePaise(rateCard(), 100_000, true);

        // Default primeSurchargeBps = 3500 (35%) — the expensive overnight window costs MORE.
        assertThat(primeCost).isEqualTo(dayCost + dayCost * 35 / 100);
        assertThat(primeCost).isGreaterThan(dayCost);
    }
}
