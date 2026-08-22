package com.oneday.orders.service.impl;

import com.oneday.orders.config.MeasurementProperties;
import com.oneday.orders.service.DiscrepancyPolicy;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
class DiscrepancyPolicyImpl implements DiscrepancyPolicy {

    private final MeasurementProperties props;

    DiscrepancyPolicyImpl(MeasurementProperties props) {
        this.props = props;
    }

    @Override
    public Verdict evaluate(double[] declared, Double[] measured) {
        if (declared == null || measured == null
                || anyNullOrNonPositive(declared) || anyNullOrNonPositive(measured)) {
            return new Verdict(false, "incomplete dimensions — not evaluated");
        }
        double[] d = declared.clone();
        double[] m = new double[]{measured[0], measured[1], measured[2]};
        Arrays.sort(d);
        Arrays.sort(m);   // size-sorted → orientation-independent comparison

        double declaredVol = d[0] * d[1] * d[2];
        double measuredVol = m[0] * m[1] * m[2];
        double volPctOver = declaredVol > 0 ? (measuredVol - declaredVol) / declaredVol * 100.0 : 0;
        boolean volumeExceeded = volPctOver > props.getTolerancePct();

        double worstSideOver = 0;
        for (int i = 0; i < 3; i++) {
            worstSideOver = Math.max(worstSideOver, m[i] - d[i]);
        }
        boolean sideExceeded = worstSideOver > props.getToleranceSideCm();

        boolean over = volumeExceeded || sideExceeded;
        if (!over) {
            return new Verdict(false, String.format(
                    "within tolerance (volume +%.0f%%, worst side +%.1fcm)", volPctOver, worstSideOver));
        }
        return new Verdict(true, String.format(
                "over-declared: volume +%.0f%% (declared %s → measured %s cm), worst side +%.1fcm",
                volPctOver, fmt(d), fmt(m), worstSideOver));
    }

    private static boolean anyNullOrNonPositive(double[] a) {
        for (double v : a) if (v <= 0) return true;
        return false;
    }

    private static boolean anyNullOrNonPositive(Double[] a) {
        for (Double v : a) if (v == null || v <= 0) return true;
        return false;
    }

    private static String fmt(double[] a) {
        return String.format("%.0fx%.0fx%.0f", a[0], a[1], a[2]);
    }
}
