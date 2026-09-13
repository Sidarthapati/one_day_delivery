package com.oneday.auth.dto.request;

import java.time.LocalDate;

/**
 * Wizard step 1 — personal details + identity numbers (document images come in slice S2). All fields
 * are optional at save time so the applicant can save progress; completeness is enforced at submit.
 */
public record CandidatePersonalRequest(
        String firstName,
        String lastName,
        String phone,
        LocalDate dob,
        String aadhaar,
        String pan,
        String drivingLicense
) {}
