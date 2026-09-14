package com.oneday.auth.domain;

/**
 * Lifecycle of a self-service DA onboarding candidate.
 *
 * <p>Happy path: {@code DRAFT → SUBMITTED → BGV_IN_PROGRESS → BGV_CLEAR → PENDING_APPROVAL → APPROVED →
 * ONBOARDED}. The BGV states are populated once slice S3 lands; until then {@code submit} moves a
 * candidate straight to {@code PENDING_APPROVAL}. {@code REJECTED} is terminal.
 */
public enum OnboardingStatus {
    DRAFT,
    SUBMITTED,
    BGV_IN_PROGRESS,
    BGV_CLEAR,
    BGV_INSUFFICIENT,
    PENDING_APPROVAL,
    APPROVED,
    ONBOARDED,
    REJECTED
}
