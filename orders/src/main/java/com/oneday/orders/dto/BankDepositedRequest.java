package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The station records the bank deposit slip reference for a handed-over deposit. */
public record BankDepositedRequest(
        @NotBlank @Size(max = 80) String bankDepositRef) {
}
