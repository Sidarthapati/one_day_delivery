package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A member verifies their own KYC by PAN (name is cross-checked against the PAN record). */
public record MemberKycRequest(
        @NotBlank @Size(max = 10) String pan,
        @NotBlank @Size(max = 200) String name) {
}
