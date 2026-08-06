package com.oneday.sla.service;

import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.SlaLeg;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The heart of M10-D-005: a deterministic buffer-aware roll-forward (not a learned ETA).
 *
 * <pre>
 * projected_finish = expected_end_of_current_leg + Σ(budget of every downstream leg not yet started)
 * RED    if projected_finish &gt; internal target
 * AMBER  if a leg overran its own budget but projected_finish ≤ target
 * GREEN  otherwise
 * </pre>
 *
 * <p>{@code expected_end_of_current_leg} prefers the leg's enrichment estimate ({@code projectedEndAt},
 * set from van-late / flight-cutoff / hub-deadline signals); absent that it is {@code started + budget},
 * floored at {@code now} (an overrunning leg can finish no earlier than now — the least-alarming
 * assumption). Pure and side-effecting only on the passed legs' {@code state}; no Spring, no I/O.</p>
 */
@Component
public class ProjectionCalculator {

    /** Result of a projection pass. */
    public record Projection(Instant projectedFinishAt, SlaState overall) {}

    /**
     * Evaluate the shipment from its ordered legs. Sets each leg's {@code state} and returns the
     * projected finish + rolled-up shipment colour (before the engine's breach upgrade).
     */
    public Projection evaluate(List<SlaLeg> legsOrdered, Instant internalTargetAt, Instant now) {
        Instant projected = projectFinish(legsOrdered, now);
        for (SlaLeg leg : legsOrdered) {
            leg.setState(legState(leg, projected, internalTargetAt, now));
        }
        return new Projection(projected, rollup(legsOrdered, projected, internalTargetAt));
    }

    private Instant projectFinish(List<SlaLeg> legs, Instant now) {
        SlaLeg current = legs.stream()
                .filter(l -> l.getStartedAt() != null && l.getCompletedAt() == null)
                .findFirst()
                .orElse(null);

        if (current == null) {
            boolean anyOpen = legs.stream().anyMatch(l -> l.getCompletedAt() == null);
            if (!anyOpen && !legs.isEmpty()) {
                // Everything completed — finish time is the last completion.
                return legs.get(legs.size() - 1).getCompletedAt();
            }
            // Nothing started yet (or a gap) — project from now through every open leg's budget.
            long remaining = legs.stream()
                    .filter(l -> l.getCompletedAt() == null)
                    .mapToLong(SlaLeg::getBudgetMinutes)
                    .sum();
            return now.plus(Duration.ofMinutes(remaining));
        }

        Instant expectedEnd = expectedEnd(current, now);
        long remainingAfter = legs.stream()
                .filter(l -> l.getSeq() > current.getSeq() && l.getCompletedAt() == null)
                .mapToLong(SlaLeg::getBudgetMinutes)
                .sum();
        return expectedEnd.plus(Duration.ofMinutes(remainingAfter));
    }

    private Instant expectedEnd(SlaLeg leg, Instant now) {
        if (leg.getProjectedEndAt() != null) {
            return leg.getProjectedEndAt().isAfter(now) ? leg.getProjectedEndAt() : now;
        }
        Instant budgetEnd = leg.getStartedAt().plus(Duration.ofMinutes(leg.getBudgetMinutes()));
        return budgetEnd.isAfter(now) ? budgetEnd : now;
    }

    private SlaState legState(SlaLeg leg, Instant projectedFinish, Instant target, Instant now) {
        if (leg.getCompletedAt() != null) {
            // Historical: within its deadline = GREEN, overran = AMBER (it ate buffer but is done).
            boolean late = leg.getDeadlineAt() != null && leg.getCompletedAt().isAfter(leg.getDeadlineAt());
            return late ? SlaState.AMBER : SlaState.GREEN;
        }
        if (leg.getStartedAt() == null) {
            return SlaState.GREEN; // not reached yet
        }
        if (projectedFinish != null && projectedFinish.isAfter(target)) {
            return SlaState.RED;
        }
        boolean overBudget = leg.getDeadlineAt() != null && now.isAfter(leg.getDeadlineAt());
        return overBudget ? SlaState.AMBER : SlaState.GREEN;
    }

    private SlaState rollup(List<SlaLeg> legs, Instant projectedFinish, Instant target) {
        boolean anyRed = legs.stream().anyMatch(l -> l.getState() == SlaState.RED);
        if (anyRed || (projectedFinish != null && projectedFinish.isAfter(target))) {
            return SlaState.RED;
        }
        boolean anyAmber = legs.stream().anyMatch(l -> l.getState() == SlaState.AMBER);
        return anyAmber ? SlaState.AMBER : SlaState.GREEN;
    }
}
