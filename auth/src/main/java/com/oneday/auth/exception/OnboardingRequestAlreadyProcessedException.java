package com.oneday.auth.exception;

public class OnboardingRequestAlreadyProcessedException extends RuntimeException {
    public OnboardingRequestAlreadyProcessedException() {
        super("Onboarding request has already been approved or rejected");
    }
}
