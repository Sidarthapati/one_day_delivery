package com.oneday.sla.service;

import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.PriorityBand;
import com.oneday.sla.domain.SlaLeg;
import com.oneday.sla.domain.SlaShipment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Turns a shipment's SLA state into a triage priority: which band it's in, the act-by deadline the
 * manager is really racing, how many minutes it is projected to miss by, and a single sortable score.
 *
 * <p>Pure and side-effect-free — the engine calls it on every recompute and persists the result so
 * the control tower is a cheap indexed {@code ORDER BY priority_score DESC}. The score encodes the
 * settled model, most-significant term first:
 * <ol>
 *   <li><b>band</b> (CRITICAL &gt; HIGH &gt; WATCH) — dominates everything;</li>
 *   <li><b>fixable</b> — a parcel the manager can still influence outranks one locked in the air;</li>
 *   <li><b>act-by</b> — the sooner the intervention window closes, the higher (the default spine);</li>
 *   <li><b>overshoot</b> — larger projected/actual SLA miss breaks ties (1h over beats 30m over).</li>
 * </ol>
 */
@Component
public class PriorityScorer {

    // Score term weights — kept far enough apart that a higher term never inverts under a lower one.
    // ponytail: weights are a calibration knob; tune against real breach outcomes, keep band dominant.
    private static final double BAND_WEIGHT = 1_000_000_000d;
    private static final double FIXABLE_WEIGHT = 1_000_000d;
    private static final long ACT_BY_HORIZON_MIN = 100_000; // an absent/far act-by sorts last in-band

    private static final int CRITICAL_ACT_BY_MIN = 30;
    private static final int HIGH_ACT_BY_MIN = 120;

    /** The computed triage result for one shipment. */
    public record Scored(PriorityBand band, Integer urgencyMinutes, Instant actByAt,
                         double score, boolean fixable) {}

    public Scored score(SlaShipment ss, List<SlaLeg> legs, Instant now) {
        Instant target = ss.getInternalTargetAt();
        Instant actByAt = nearestOpenLegDeadline(legs);
        boolean fixable = isFixable(ss.getCurrentLeg());

        // Minutes we are projected (or already) to miss the internal target by. Positive = over,
        // negative = slack remaining. Breach measures from now (it only grows); else from projection.
        Integer urgencyMinutes = null;
        if (target != null) {
            Instant finish = ss.isBreached()
                    ? now
                    : (ss.getProjectedFinishAt() != null ? ss.getProjectedFinishAt() : now);
            urgencyMinutes = (int) minutesBetween(target, finish);
        }

        PriorityBand band = band(ss, actByAt, now);
        double score = score(band, fixable, actByAt, urgencyMinutes, now);
        return new Scored(band, urgencyMinutes, actByAt, score, fixable);
    }

    private PriorityBand band(SlaShipment ss, Instant actByAt, Instant now) {
        boolean promiseBlown = ss.getPublicPromiseAt() != null && now.isAfter(ss.getPublicPromiseAt());
        long actByMin = actByAt == null ? Long.MAX_VALUE : minutesBetween(now, actByAt);

        if (ss.isBreached() || promiseBlown || actByMin <= CRITICAL_ACT_BY_MIN) {
            return PriorityBand.CRITICAL;
        }
        if (ss.getOverallState() == SlaState.RED || actByMin <= HIGH_ACT_BY_MIN) {
            return PriorityBand.HIGH;
        }
        return PriorityBand.WATCH;
    }

    private double score(PriorityBand band, boolean fixable, Instant actByAt,
                         Integer urgencyMinutes, Instant now) {
        double s = band.rank() * BAND_WEIGHT;
        s += (fixable ? 1 : 0) * FIXABLE_WEIGHT;
        // Sooner act-by → higher, in [0, ACT_BY_HORIZON_MIN]. Null act-by contributes 0 (sorts last).
        long actByMin = actByAt == null ? ACT_BY_HORIZON_MIN : Math.max(0, minutesBetween(now, actByAt));
        s += Math.max(0, ACT_BY_HORIZON_MIN - actByMin);
        // Overshoot tie-break — small weight so it never crosses an act-by band inside the same band.
        if (urgencyMinutes != null) {
            s += Math.max(0, urgencyMinutes) / 1000.0;
        }
        return s;
    }

    /** The soonest deadline among legs still open — the nearest hard window the manager is racing. */
    private Instant nearestOpenLegDeadline(List<SlaLeg> legs) {
        return legs.stream()
                .filter(l -> l.getCompletedAt() == null && l.getDeadlineAt() != null)
                .map(SlaLeg::getDeadlineAt)
                .min(Instant::compareTo)
                .orElse(null);
    }

    /**
     * Can the station manager still change the outcome? Everything but a parcel in the air is
     * intervenable (reassign DA, expedite hub, push next flight, call GHA). A parcel mid-AIR is
     * locked — it ranks below fixable peers in the same band.
     */
    private boolean isFixable(SlaLegType currentLeg) {
        return currentLeg != SlaLegType.AIR;
    }

    private static long minutesBetween(Instant from, Instant to) {
        return Duration.between(from, to).toMinutes();
    }
}
