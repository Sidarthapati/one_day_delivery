package com.oneday.orders.service.impl;

import com.oneday.orders.config.MeasurementProperties;
import com.oneday.orders.service.DiscrepancyPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Boundary behaviour of the moderate tolerance (volume +10% OR any side +2cm), orientation-independent. */
class DiscrepancyPolicyImplTest {

    private DiscrepancyPolicy policy;

    @BeforeEach
    void setUp() {
        MeasurementProperties props = new MeasurementProperties(); // 10% volume, 2cm side
        policy = new DiscrepancyPolicyImpl(props);
    }

    @Test
    void withinToleranceNotFlagged() {
        // declared 30x20x10, measured 31x20x10 → +1cm side, volume +3.3% → within both
        var v = policy.evaluate(new double[]{30, 20, 10}, new Double[]{31.0, 20.0, 10.0});
        assertThat(v.overDeclared()).isFalse();
    }

    @Test
    void bigUnderDeclarationFlaggedByVolume() {
        // declared 30x20x10 (6000), measured 35x25x15 (13125) → +118% volume
        var v = policy.evaluate(new double[]{30, 20, 10}, new Double[]{35.0, 25.0, 15.0});
        assertThat(v.overDeclared()).isTrue();
        assertThat(v.detail()).contains("over-declared");
    }

    @Test
    void singleSideOverThresholdFlagged() {
        // declared 30x20x10 (6000), measured 33x20x9.5 (6270) → volume only +4.5% (within tolerance),
        // but the longest side is +3cm → the SIDE rule alone must trip (volume rule stays silent).
        var v = policy.evaluate(new double[]{30, 20, 10}, new Double[]{33.0, 20.0, 9.5});
        assertThat(v.overDeclared()).isTrue();
    }

    @Test
    void orientationIndependent() {
        // same box, axes permuted → must NOT be flagged (sizes sorted before compare)
        var v = policy.evaluate(new double[]{30, 20, 10}, new Double[]{10.0, 30.0, 20.0});
        assertThat(v.overDeclared()).isFalse();
    }

    @Test
    void incompleteDimensionsNotFlagged() {
        var v = policy.evaluate(new double[]{30, 20, 10}, new Double[]{30.0, null, 10.0});
        assertThat(v.overDeclared()).isFalse();
        assertThat(v.detail()).contains("incomplete");
    }
}
