package com.oneday.auth.service;

import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.auth.exception.InvalidOnboardingTransitionException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.oneday.auth.domain.OnboardingStatus.*;

/**
 * The allowed status transitions for a DA onboarding candidate. Kept deliberately small — a table of
 * from → allowed-targets — so the funnel's legal moves are declared in one place and any illegal move
 * (e.g. approving a DRAFT, editing an APPROVED) is rejected with a 409.
 *
 * <p>The BGV branch ({@code SUBMITTED → BGV_IN_PROGRESS → …}) is present now; until slice S3 wires the
 * mock vendor, {@code submit} takes the direct {@code SUBMITTED → PENDING_APPROVAL} edge.
 */
@Component
public class OnboardingStateMachine {

    private static final Map<OnboardingStatus, Set<OnboardingStatus>> ALLOWED =
            new EnumMap<>(OnboardingStatus.class);

    static {
        ALLOWED.put(DRAFT, EnumSet.of(SUBMITTED, REJECTED));
        ALLOWED.put(SUBMITTED, EnumSet.of(BGV_IN_PROGRESS, PENDING_APPROVAL, REJECTED));
        ALLOWED.put(BGV_IN_PROGRESS, EnumSet.of(BGV_CLEAR, BGV_INSUFFICIENT, REJECTED));
        ALLOWED.put(BGV_CLEAR, EnumSet.of(PENDING_APPROVAL, REJECTED));
        ALLOWED.put(BGV_INSUFFICIENT, EnumSet.of(PENDING_APPROVAL, REJECTED));
        ALLOWED.put(PENDING_APPROVAL, EnumSet.of(APPROVED, REJECTED));
        ALLOWED.put(APPROVED, EnumSet.of(ONBOARDED));
        ALLOWED.put(ONBOARDED, EnumSet.noneOf(OnboardingStatus.class));
        ALLOWED.put(REJECTED, EnumSet.noneOf(OnboardingStatus.class));
    }

    /** @return true if {@code from → to} is a legal transition. */
    public boolean canTransition(OnboardingStatus from, OnboardingStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(OnboardingStatus.class)).contains(to);
    }

    /** Assert the transition is legal, else throw {@link InvalidOnboardingTransitionException} (409). */
    public void assertCanTransition(OnboardingStatus from, OnboardingStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidOnboardingTransitionException(
                    "Cannot move onboarding candidate from " + from + " to " + to);
        }
    }
}
