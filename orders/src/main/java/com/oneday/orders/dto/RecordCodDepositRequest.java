package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A delivery associate records a COD cash deposit (bank/hub). {@code depositRef} is required — it is the
 * idempotency key for the deposit (and the ledger movement it posts), so a retried submit can't
 * double-count cash.
 */
public record RecordCodDepositRequest(
        @Positive Long amountPaise,
        @NotBlank @Size(max = 80) String depositRef,
        @Size(max = 300) String note) {
}
