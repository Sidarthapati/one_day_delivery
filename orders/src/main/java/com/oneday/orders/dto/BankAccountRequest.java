package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A merchant submitting/replacing the bank account COD is remitted to. */
public record BankAccountRequest(
        @NotBlank @Pattern(regexp = "\\d{9,18}", message = "Account number must be 9–18 digits")
        String accountNumber,

        @NotBlank @Pattern(regexp = "^[A-Za-z]{4}0[A-Za-z0-9]{6}$", message = "Invalid IFSC")
        String ifsc,

        @NotBlank @Size(max = 200)
        String beneficiaryName,

        @Size(max = 120)
        String bankName,

        // Optional comma-separated emails to notify on each payout.
        @Size(max = 500)
        String notifyEmails) {}
