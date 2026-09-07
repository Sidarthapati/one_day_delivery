package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The station cashier enters the DA's handoff code to confirm receipt of the cash. */
public record VerifyHandoffRequest(
        @NotBlank @Size(max = 8) String otp) {
}
