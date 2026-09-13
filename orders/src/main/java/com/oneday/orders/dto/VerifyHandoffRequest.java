package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The station cashier confirms receipt of the cash: enters the DA's handoff code AND their own
 * independent count (Discussion-4 G1). If {@code countedAmountPaise} differs from the DA's declared
 * amount, the deposit is flagged as a discrepancy rather than handed over.
 */
public record VerifyHandoffRequest(
        @NotBlank @Size(max = 8) String otp,
        @NotNull @PositiveOrZero Long countedAmountPaise) {
}
