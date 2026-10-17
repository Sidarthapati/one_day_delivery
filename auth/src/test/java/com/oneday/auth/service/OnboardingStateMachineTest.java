package com.oneday.auth.service;

import com.oneday.auth.exception.InvalidOnboardingTransitionException;
import org.junit.jupiter.api.Test;

import static com.oneday.auth.domain.OnboardingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class OnboardingStateMachineTest {

    private final OnboardingStateMachine sm = new OnboardingStateMachine();

    @Test
    void happyPath_transitionsAreLegal() {
        assertThat(sm.canTransition(DRAFT, SUBMITTED)).isTrue();
        assertThat(sm.canTransition(SUBMITTED, PENDING_APPROVAL)).isTrue();   // S1 direct edge (no BGV)
        assertThat(sm.canTransition(SUBMITTED, BGV_IN_PROGRESS)).isTrue();    // S3 edge
        assertThat(sm.canTransition(BGV_IN_PROGRESS, BGV_CLEAR)).isTrue();
        assertThat(sm.canTransition(BGV_CLEAR, PENDING_APPROVAL)).isTrue();
        assertThat(sm.canTransition(PENDING_APPROVAL, APPROVED)).isTrue();
        assertThat(sm.canTransition(APPROVED, ONBOARDED)).isTrue();
    }

    @Test
    void rejectIsReachableFromEveryNonTerminalState() {
        assertThat(sm.canTransition(DRAFT, REJECTED)).isTrue();
        assertThat(sm.canTransition(SUBMITTED, REJECTED)).isTrue();
        assertThat(sm.canTransition(BGV_IN_PROGRESS, REJECTED)).isTrue();
        assertThat(sm.canTransition(PENDING_APPROVAL, REJECTED)).isTrue();
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThat(sm.canTransition(DRAFT, APPROVED)).isFalse();       // cannot skip the queue
        assertThat(sm.canTransition(PENDING_APPROVAL, ONBOARDED)).isFalse(); // must go through APPROVED
        assertThat(sm.canTransition(APPROVED, REJECTED)).isFalse();    // approved is not rejectable
    }

    @Test
    void terminalStatesHaveNoExit() {
        assertThat(sm.canTransition(ONBOARDED, APPROVED)).isFalse();
        assertThat(sm.canTransition(REJECTED, DRAFT)).isFalse();
    }

    @Test
    void assertCanTransition_throwsOnIllegalMove() {
        assertThatThrownBy(() -> sm.assertCanTransition(DRAFT, ONBOARDED))
                .isInstanceOf(InvalidOnboardingTransitionException.class);
        assertThatCode(() -> sm.assertCanTransition(PENDING_APPROVAL, APPROVED))
                .doesNotThrowAnyException();
    }
}
