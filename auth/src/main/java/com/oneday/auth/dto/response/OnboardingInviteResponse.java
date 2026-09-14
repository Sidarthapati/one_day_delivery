package com.oneday.auth.dto.response;

import com.oneday.auth.domain.OnboardingStatus;

import java.util.UUID;

/**
 * Result of opening an onboarding funnel. {@code inviteUrl} is the link handed to the applicant (built
 * from {@code godspeed.onboarding.invite-base-url} + the token); there is no email service in the pilot.
 */
public record OnboardingInviteResponse(
        UUID candidateId,
        String inviteToken,
        String inviteUrl,
        OnboardingStatus status
) {}
