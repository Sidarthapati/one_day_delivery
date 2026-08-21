package com.oneday.sla.service;

import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.PriorityBand;
import com.oneday.sla.domain.SlaLeg;
import com.oneday.sla.domain.SlaShipment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The triage priority ordering invariants — the model must never invert these. */
class PriorityScorerTest {

    private final PriorityScorer scorer = new PriorityScorer();
    private final Instant now = Instant.parse("2026-08-21T12:00:00Z");

    private SlaShipment ship(SlaState state, boolean breached, SlaLegType currentLeg,
                             Instant target, Instant projectedFinish, Instant publicPromise) {
        SlaShipment s = new SlaShipment();
        s.setOverallState(state);
        s.setBreached(breached);
        s.setCurrentLeg(currentLeg);
        s.setInternalTargetAt(target);
        s.setProjectedFinishAt(projectedFinish);
        s.setPublicPromiseAt(publicPromise);
        return s;
    }

    /** One open leg with the given deadline (drives act-by). */
    private List<SlaLeg> legDue(Instant deadline) {
        SlaLeg l = new SlaLeg();
        l.setLeg(SlaLegType.LAST_MILE);
        l.setSeq(1);
        l.setBudgetMinutes(60);
        l.setDeadlineAt(deadline);
        return List.of(l);
    }

    private Instant plus(int min) { return now.plus(Duration.ofMinutes(min)); }
    private Instant minus(int min) { return now.minus(Duration.ofMinutes(min)); }

    @Test
    void breachedIsCriticalAndOutranksRed() {
        var breached = scorer.score(
                ship(SlaState.BREACHED, true, SlaLegType.DEST_HUB, minus(60), now, null),
                legDue(minus(10)), now);
        var red = scorer.score(
                ship(SlaState.RED, false, SlaLegType.DEST_HUB, plus(60), plus(120), null),
                legDue(plus(90)), now);

        assertThat(breached.band()).isEqualTo(PriorityBand.CRITICAL);
        assertThat(red.band()).isEqualTo(PriorityBand.HIGH);
        assertThat(breached.score()).isGreaterThan(red.score());
    }

    @Test
    void deeperBreachOutranksShallower() {
        var deep = scorer.score(ship(SlaState.BREACHED, true, SlaLegType.DEST_HUB, minus(60), now, null),
                List.of(), now);          // no open leg → act-by null on both, so overshoot decides
        var shallow = scorer.score(ship(SlaState.BREACHED, true, SlaLegType.DEST_HUB, minus(30), now, null),
                List.of(), now);
        assertThat(deep.urgencyMinutes()).isGreaterThan(shallow.urgencyMinutes());
        assertThat(deep.score()).isGreaterThan(shallow.score());
    }

    @Test
    void fixableOutranksLockedInSameBand() {
        var fixable = scorer.score(ship(SlaState.RED, false, SlaLegType.DEST_HUB, plus(60), plus(120), null),
                legDue(plus(90)), now);
        var locked = scorer.score(ship(SlaState.RED, false, SlaLegType.AIR, plus(60), plus(120), null),
                legDue(plus(90)), now);
        assertThat(fixable.fixable()).isTrue();
        assertThat(locked.fixable()).isFalse();
        assertThat(fixable.band()).isEqualTo(locked.band());
        assertThat(fixable.score()).isGreaterThan(locked.score());
    }

    @Test
    void soonerActByOutranksLaterInSameBand() {
        var soon = scorer.score(ship(SlaState.RED, false, SlaLegType.DEST_HUB, plus(300), plus(360), null),
                legDue(plus(40)), now);   // 40m: >30 (not critical), ≤120 → HIGH
        var later = scorer.score(ship(SlaState.RED, false, SlaLegType.DEST_HUB, plus(300), plus(360), null),
                legDue(plus(90)), now);
        assertThat(soon.band()).isEqualTo(PriorityBand.HIGH);
        assertThat(later.band()).isEqualTo(PriorityBand.HIGH);
        assertThat(soon.score()).isGreaterThan(later.score());
    }

    @Test
    void imminentActByForcesCriticalEvenWhenColourIsGreen() {
        var s = scorer.score(
                ship(SlaState.GREEN, false, SlaLegType.FIRST_MILE, plus(600), plus(120), null),
                legDue(plus(15)), now);   // must act in 15m regardless of the healthy colour
        assertThat(s.band()).isEqualTo(PriorityBand.CRITICAL);
    }

    @Test
    void publicPromiseBreachIsCritical() {
        var s = scorer.score(
                ship(SlaState.AMBER, false, SlaLegType.LAST_MILE, plus(120), plus(60), minus(5)),
                legDue(plus(200)), now);  // customer 24h promise already blown
        assertThat(s.band()).isEqualTo(PriorityBand.CRITICAL);
    }

    @Test
    void weatherExposureLiftsWithinBandButNeverAcrossIt() {
        SlaShipment toBom = ship(SlaState.GREEN, false, SlaLegType.LAST_MILE, plus(600), plus(120), null);
        toBom.setOriginCity("DEL"); toBom.setDestCity("BOM");
        var dry = scorer.score(toBom, legDue(plus(300)), now, java.util.Set.of());
        var rainy = scorer.score(toBom, legDue(plus(300)), now, java.util.Set.of("BOM")); // dest adverse

        assertThat(rainy.weatherExposed()).isTrue();
        assertThat(dry.weatherExposed()).isFalse();
        assertThat(rainy.band()).isEqualTo(dry.band());                 // still WATCH — no band jump
        assertThat(rainy.score()).isGreaterThan(dry.score());           // but ranks above its dry twin

        // A weather-exposed WATCH parcel must never outrank a real breach.
        var breached = scorer.score(
                ship(SlaState.BREACHED, true, SlaLegType.DEST_HUB, minus(60), now, null),
                legDue(minus(5)), now, java.util.Set.of("BOM"));
        assertThat(breached.score()).isGreaterThan(rainy.score());
    }

    @Test
    void aJustEscalatedParcelOutranksAStaleOneInTheSameBand() {
        SlaShipment fresh = ship(SlaState.BREACHED, true, SlaLegType.DEST_HUB, minus(60), now, null);
        fresh.setEnteredStateAt(now);           // new fire
        SlaShipment stale = ship(SlaState.BREACHED, true, SlaLegType.DEST_HUB, minus(60), now, null);
        stale.setEnteredStateAt(minus(60));     // been critical an hour → boost decayed to zero

        var f = scorer.score(fresh, legDue(minus(5)), now);
        var s = scorer.score(stale, legDue(minus(5)), now);
        assertThat(f.band()).isEqualTo(s.band());
        assertThat(f.score()).isGreaterThan(s.score());
    }

    @Test
    void weatherOnlyExposesGroundLegsHeadingIntoTheAdverseCity() {
        // In the air → not exposed (can't act, and dest weather isn't the parcel's ground risk yet).
        SlaShipment inAir = ship(SlaState.GREEN, false, SlaLegType.AIR, plus(600), plus(120), null);
        inAir.setOriginCity("DEL"); inAir.setDestCity("BOM");
        assertThat(scorer.score(inAir, legDue(plus(300)), now, java.util.Set.of("BOM")).weatherExposed()).isFalse();
    }
}
