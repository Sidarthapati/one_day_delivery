package com.oneday.auth.dto.response;

import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.common.domain.Shift;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Full view of an onboarding candidate — serves both the applicant's wizard (fetched by token) and the
 * reviewer's detail view. Bank/identity numbers are returned as-is for this internal tool.
 */
public record CandidateResponse(
        UUID id,
        String inviteToken,
        OnboardingStatus status,
        String firstName,
        String lastName,
        String email,
        String phone,
        LocalDate dob,
        String aadhaar,
        String pan,
        String drivingLicense,
        String cityId,
        String stationLabel,
        Shift shift,
        String bankAccountNumber,
        String ifsc,
        String accountHolderName,
        Instant agreementAcceptedAt,
        Instant trainingAckAt,
        Instant submittedAt,
        UUID approvedBy,
        Instant approvedAt,
        String rejectionReason,
        UUID provisionedUserId,
        String employeeId,
        Instant createdAt,
        Instant updatedAt
) {}
