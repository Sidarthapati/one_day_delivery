package com.oneday.auth.dto.request;

import com.oneday.common.domain.Shift;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Admin-driven DA registration. Aadhaar / PAN / PAN document are optional for the pilot; phone is
 * optional; {@code contractEndDate} null = open-ended; {@code password} null = a temp password is
 * generated and returned once in {@link com.oneday.auth.dto.response.DaResponse}.
 */
public record RegisterDaRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank @Email String email,
        String phone,
        @NotBlank String cityId,
        @NotNull Shift shift,
        @NotNull LocalDate contractStartDate,
        LocalDate contractEndDate,
        String aadhaar,
        String pan,
        String panDocUrl,
        String password
) {}
