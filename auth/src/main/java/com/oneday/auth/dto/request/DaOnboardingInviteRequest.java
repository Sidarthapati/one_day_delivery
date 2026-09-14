package com.oneday.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Admin/station-manager action that opens a DA onboarding funnel: creates a DRAFT candidate and returns
 * an invite link for the applicant to complete. Only the email is strictly required (it keys the
 * candidate and the eventual user); name/phone/city pre-fill the wizard and can be edited by the applicant.
 */
public record DaOnboardingInviteRequest(
        @NotBlank @Email String email,
        String firstName,
        String lastName,
        String phone,
        String cityId
) {}
