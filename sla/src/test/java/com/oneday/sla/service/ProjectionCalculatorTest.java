package com.oneday.sla.service;

import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.SlaLeg;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The buffer-aware projection (M10-D-005). Budgets sum to 13.5h under a 16h target, so a leg can
 * overrun its own budget and stay AMBER while the projection still clears the target; it only flips
 * RED once the overrun eats the 2.5h internal cushion.
 */
class ProjectionCalculatorTest {

    private final ProjectionCalculator calc = new ProjectionCalculator();

    // INTERCITY budgets (minutes) from application.yml; Σ = 810 (13.5h).
    private static final int[] BUDGETS = {180, 60, 120, 150, 90, 60, 150};
    private static final SlaLegType[] LEGS = SlaLegType.values(); // 7, in order
    private final Instant t0 = Instant.parse("2026-07-17T02:30:00Z"); // 08:00 IST
    private final Instant target = t0.plus(Duration.ofHours(16));

    private List<SlaLeg> freshPlan() {
        List<SlaLeg> legs = new ArrayList<>();
        for (int i = 0; i < LEGS.length; i++) {
            SlaLeg l = new SlaLeg();
            l.setLeg(LEGS[i]);
            l.setSeq(i);
            l.setBudgetMinutes(BUDGETS[i]);
            legs.add(l);
        }
        // First mile live from booking.
        legs.get(0).setStartedAt(t0);
        legs.get(0).setDeadlineAt(t0.plus(Duration.ofMinutes(BUDGETS[0])));
        return legs;
    }

    private Instant at(long minutes) {
        return t0.plus(Duration.ofMinutes(minutes));
    }

    @Test
    void onTrack_isGreen() {
        List<SlaLeg> legs = freshPlan();
        ProjectionCalculator.Projection p = calc.evaluate(legs, target, at(60));
        assertThat(p.overall()).isEqualTo(SlaState.GREEN);
        assertThat(p.projectedFinishAt()).isBeforeOrEqualTo(target);
        assertThat(legs.get(0).getState()).isEqualTo(SlaState.GREEN);
    }

    @Test
    void legOverBudget_butProjectionClearsTarget_isAmber() {
        List<SlaLeg> legs = freshPlan();
        // First mile 120m past its 180m budget, still open; projection stays under the 16h target.
        ProjectionCalculator.Projection p = calc.evaluate(legs, target, at(300));
        assertThat(legs.get(0).getState()).isEqualTo(SlaState.AMBER);
        assertThat(p.overall()).isEqualTo(SlaState.AMBER);
        assertThat(p.projectedFinishAt()).isBefore(target);
    }

    @Test
    void overrunEatsTheCushion_isRed() {
        List<SlaLeg> legs = freshPlan();
        // First mile 220m past budget — projection now crosses the 16h target.
        ProjectionCalculator.Projection p = calc.evaluate(legs, target, at(400));
        assertThat(p.projectedFinishAt()).isAfter(target);
        assertThat(legs.get(0).getState()).isEqualTo(SlaState.RED);
        assertThat(p.overall()).isEqualTo(SlaState.RED);
    }

    @Test
    void completedLateLeg_staysAmberInHistory() {
        List<SlaLeg> legs = freshPlan();
        // First mile completed 70m late; origin-hub now live and on time.
        legs.get(0).setCompletedAt(at(250));
        legs.get(1).setStartedAt(at(250));
        legs.get(1).setDeadlineAt(at(250 + 60));
        ProjectionCalculator.Projection p = calc.evaluate(legs, target, at(280));
        assertThat(legs.get(0).getState()).isEqualTo(SlaState.AMBER);
        assertThat(p.overall()).isEqualTo(SlaState.AMBER);
    }

    @Test
    void completedOnTimeLeg_isGreenHistory() {
        List<SlaLeg> legs = freshPlan();
        legs.get(0).setCompletedAt(at(150)); // within 180 budget
        legs.get(1).setStartedAt(at(150));
        legs.get(1).setDeadlineAt(at(210));
        ProjectionCalculator.Projection p = calc.evaluate(legs, target, at(180));
        assertThat(legs.get(0).getState()).isEqualTo(SlaState.GREEN);
        assertThat(p.overall()).isEqualTo(SlaState.GREEN);
    }

    @Test
    void enrichmentEstimate_overridesBudget() {
        List<SlaLeg> legs = freshPlan();
        // A late signal on the open first mile: it will actually finish at t0+900, blowing the target.
        legs.get(0).setProjectedEndAt(at(900));
        ProjectionCalculator.Projection p = calc.evaluate(legs, target, at(60));
        assertThat(p.projectedFinishAt()).isAfter(target);
        assertThat(p.overall()).isEqualTo(SlaState.RED);
    }
}
