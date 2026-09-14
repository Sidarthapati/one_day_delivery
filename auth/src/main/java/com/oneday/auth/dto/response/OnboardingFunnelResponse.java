package com.oneday.auth.dto.response;

/**
 * Onboarding funnel summary for the admin dashboard (the Amazon "onboarding funnel" shape): how many
 * candidates sit at each stage, plus the overall conversion rate (onboarded ÷ total).
 */
public record OnboardingFunnelResponse(
        long total,
        long draft,
        long submitted,
        long inBgv,
        long pendingApproval,
        long onboarded,
        long rejected,
        double conversionRate
) {}
