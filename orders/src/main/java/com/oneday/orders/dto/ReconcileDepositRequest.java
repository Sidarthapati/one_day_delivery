package com.oneday.orders.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin verdict on a DA's cash deposit: matched → RECONCILED, else DISCREPANCY. */
public record ReconcileDepositRequest(
        @NotNull Boolean reconciled,
        @Size(max = 300) String note) {
}
