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
import java.util.Set;

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
    // Weather nudge: capped well below the act-by term so a weather-exposed parcel rises within its
    // band (a rainy last-mile beats a dry one) but never outranks an actual breach or a sooner cutoff.
    // ponytail: calibration knob.
    private static final double WEATHER_WEIGHT = 40_000d;
    // Freshness (trajectory): a parcel that *just* escalated is a new fire the manager hasn't seen;
    // it should surface above the stale ones already being worked. Full boost on entry, decaying to
    // zero over the window. Only in the urgent bands — a fresh WATCH parcel isn't a fire.
    // ponytail: calibration knob; velocity (Δ projected-finish) is a later refinement.
    private static final double FRESHNESS_WEIGHT = 30_000d;
    private static final long FRESHNESS_WINDOW_MIN = 20;
    // Ack decay (R4, anti-fatigue): once a manager attends to a parcel, sink it within its band so the
    // queue keeps showing new fires. Weight sits just above the whole within-band range (fixable + act-by
    // + freshness + weather ≈ 1.13e6) so a worked parcel drops below its un-worked band-peers but never
    // crosses into a lower band — an acked breach is still a breach, just handled. Decays back to zero
    // over the cooldown so an unresolved parcel resurfaces. ponytail: calibration knob.
    private static final double ACK_DECAY_WEIGHT = 2_000_000d;
    private static final long ACK_COOLDOWN_MIN = 30;

    private static final int CRITICAL_ACT_BY_MIN = 30;
    private static final int HIGH_ACT_BY_MIN = 120;
    // Band hysteresis (R5): the 60s sweeper recomputes every parcel, so a metric hovering on a band
    // boundary would flip the parcel in and out of the manager's top bucket each pass — a jittery list.
    // Promotion (getting worse) is always immediate; DEMOTION requires the act-by to clear the boundary
    // by this deadband, so a parcel that merely wobbles around 120m stays put. ponytail: calibration knob.
    private static final int BAND_HYSTERESIS_MIN = 15;

    /** The computed triage result for one shipment. */
    public record Scored(PriorityBand band, Integer urgencyMinutes, Instant actByAt,
                         double score, boolean fixable, boolean weatherExposed, boolean acknowledged) {}

    /** No-weather overload (event lifecycle paths where a live weather read isn't warranted). */
    public Scored score(SlaShipment ss, List<SlaLeg> legs, Instant now) {
        return score(ss, legs, now, Set.of());
    }

    public Scored score(SlaShipment ss, List<SlaLeg> legs, Instant now, Set<String> adverseCities) {
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

        boolean weatherExposed = isWeatherExposed(ss, adverseCities);
        PriorityBand band = band(ss, actByAt, now);
        double score = score(band, fixable, actByAt, urgencyMinutes, weatherExposed,
                freshnessBoost(band, ss.getEnteredStateAt(), now), now);
        score += ackDecay(ss, now);
        return new Scored(band, urgencyMinutes, actByAt, score, fixable, weatherExposed, ackIsLive(ss, now));
    }

    /**
     * Has a manager attended to this parcel's <em>current</em> state, with the cooldown not yet lapsed?
     * A worsening after the ack (a later {@code enteredStateAt}) is a fresh fire the ack no longer
     * covers, so the parcel resurfaces. Shared by the score's decay and the "being handled" summary flag.
     */
    public static boolean ackIsLive(SlaShipment ss, Instant now) {
        Instant acked = ss.getAcknowledgedAt();
        if (acked == null) {
            return false;
        }
        if (ss.getEnteredStateAt() != null && ss.getEnteredStateAt().isAfter(acked)) {
            return false;
        }
        long mins = minutesBetween(acked, now);
        return mins >= 0 && mins < ACK_COOLDOWN_MIN;
    }

    /** A negative, decaying term that sinks an acknowledged parcel within its band over the cooldown. */
    private double ackDecay(SlaShipment ss, Instant now) {
        if (!ackIsLive(ss, now)) {
            return 0;
        }
        long mins = Math.max(0, minutesBetween(ss.getAcknowledgedAt(), now));
        double decay = 1.0 - (double) mins / ACK_COOLDOWN_MIN;
        return -ACK_DECAY_WEIGHT * decay;
    }

    /** A decaying nudge for a parcel that just entered an urgent band — new fires over stale ones. */
    private double freshnessBoost(PriorityBand band, Instant enteredStateAt, Instant now) {
        if (band == PriorityBand.WATCH || enteredStateAt == null) {
            return 0;
        }
        long minsInState = Math.max(0, minutesBetween(enteredStateAt, now));
        double decay = Math.max(0, 1.0 - (double) minsInState / FRESHNESS_WINDOW_MIN);
        return FRESHNESS_WEIGHT * decay;
    }

    /** True when the parcel is on a ground leg heading into (or picked up in) a city with adverse weather. */
    private boolean isWeatherExposed(SlaShipment ss, Set<String> adverseCities) {
        if (adverseCities.isEmpty() || !WeatherService.isWeatherExposedLeg(ss.getCurrentLeg())) {
            return false;
        }
        String city = WeatherService.relevantCity(ss.getCurrentLeg(), ss.getOriginCity(), ss.getDestCity());
        return city != null && adverseCities.contains(city);
    }

    private PriorityBand band(SlaShipment ss, Instant actByAt, Instant now) {
        boolean promiseBlown = ss.getPublicPromiseAt() != null && now.isAfter(ss.getPublicPromiseAt());
        long actByMin = actByAt == null ? Long.MAX_VALUE : minutesBetween(now, actByAt);
        boolean red = ss.getOverallState() == SlaState.RED;
        PriorityBand prev = ss.getBand() != null ? ss.getBand() : PriorityBand.WATCH;

        // Entry thresholds — what promotes a parcel into a band.
        boolean critEnter = ss.isBreached() || promiseBlown || actByMin <= CRITICAL_ACT_BY_MIN;
        boolean highEnter = red || actByMin <= HIGH_ACT_BY_MIN;
        // Hold thresholds — relaxed by the deadband, so a parcel already in the band doesn't fall out on a
        // wobble. (breach/promise/RED are hard facts, not boundaries, so they carry no deadband.)
        boolean critHold = ss.isBreached() || promiseBlown || actByMin <= CRITICAL_ACT_BY_MIN + BAND_HYSTERESIS_MIN;
        boolean highHold = red || actByMin <= HIGH_ACT_BY_MIN + BAND_HYSTERESIS_MIN;

        if (critEnter) return PriorityBand.CRITICAL;
        if (prev == PriorityBand.CRITICAL && critHold) return PriorityBand.CRITICAL; // resist CRITICAL→lower
        if (highEnter) return PriorityBand.HIGH;
        if (prev != PriorityBand.WATCH && highHold) return PriorityBand.HIGH;        // resist →WATCH
        return PriorityBand.WATCH;
    }

    private double score(PriorityBand band, boolean fixable, Instant actByAt,
                         Integer urgencyMinutes, boolean weatherExposed, double freshnessBoost, Instant now) {
        double s = band.rank() * BAND_WEIGHT;
        s += (fixable ? 1 : 0) * FIXABLE_WEIGHT;
        // Sooner act-by → higher, in [0, ACT_BY_HORIZON_MIN]. Null act-by contributes 0 (sorts last).
        long actByMin = actByAt == null ? ACT_BY_HORIZON_MIN : Math.max(0, minutesBetween(now, actByAt));
        s += Math.max(0, ACT_BY_HORIZON_MIN - actByMin);
        s += freshnessBoost;
        if (weatherExposed) {
            s += WEATHER_WEIGHT;
        }
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
