package com.oneday.auth.exception;

/** A wizard step or submit was attempted with missing/invalid required data (→ 422). */
public class OnboardingValidationException extends RuntimeException {
    public OnboardingValidationException(String message) {
        super(message);
    }
}
