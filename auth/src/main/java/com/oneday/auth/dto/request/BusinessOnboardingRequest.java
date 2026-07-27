package com.oneday.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Business (B2B_USER) self-onboarding. The requested role is always B2B_USER; KYC (GSTIN + PAN)
 * runs at submit and an ADMIN reviews before the account is provisioned. Bank/pickup/funding are
 * captured post-approval on the account (orders), not here.
 */
public record BusinessOnboardingRequest(
        @NotBlank @Email String email,
        @NotBlank String name,
        @NotBlank @Size(min = 8) String password,
        @NotBlank @Pattern(regexp = "\\+91[0-9]{10}",
                message = "must be E.164 format (+91XXXXXXXXXX)") String phone,
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Pattern(regexp = "PROPRIETORSHIP|PARTNERSHIP|PRIVATE_LIMITED|LLP",
                message = "must be PROPRIETORSHIP, PARTNERSHIP, PRIVATE_LIMITED or LLP") String businessType,
        @NotBlank @Pattern(regexp = "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]",
                message = "must be a valid 15-character GSTIN") String gstin,
        @NotBlank @Pattern(regexp = "[A-Z]{5}[0-9]{4}[A-Z]",
                message = "must be a valid 10-character PAN") String pan,
        @NotBlank @Email String billingEmail,
        @NotBlank @Size(max = 10) String cityId,
        // Self-declared monthly parcel volume band. Optional (older clients omit it); when present
        // it must be one of the known bands. "2000+" (and up) marks a large merchant → ADMIN review.
        @Pattern(regexp = "0-200|200-500|500-1000|1000-2000|2000\\+",
                message = "must be a known volume band") String expectedMonthlyOrders
) {}
