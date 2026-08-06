package com.oneday.orders.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** A delivery associate records a COD cash deposit (bank/hub). */
public record RecordCodDepositRequest(
        @Positive Long amountPaise,
        @Size(max = 80) String depositRef,
        @Size(max = 300) String note) {
}
