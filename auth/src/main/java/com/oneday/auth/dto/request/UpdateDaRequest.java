package com.oneday.auth.dto.request;

import com.oneday.common.domain.Shift;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Edit a DA's profile / contract / shift. Identity (email) and city are not changed here. */
public record UpdateDaRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String phone,
        @NotNull Shift shift,
        @NotNull LocalDate contractStartDate,
        LocalDate contractEndDate,
        String aadhaar,
        String pan,
        String panDocUrl
) {}
