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
}
