package com.oneday.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OnboardingSubmitRequest(
        @NotBlank @Email String email,
        @NotBlank String name,
        @NotBlank @Size(min = 8) String password,
        @NotBlank @Pattern(regexp = "\\+91[0-9]{10}",
                message = "must be E.164 format (+91XXXXXXXXXX)") String phone,
        @NotBlank @Pattern(regexp = "B2B_USER|B2C_CUSTOMER",
                message = "must be B2B_USER or B2C_CUSTOMER") String requestedRole
) {}
