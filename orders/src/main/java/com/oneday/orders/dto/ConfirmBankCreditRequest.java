package com.oneday.orders.dto;

import jakarta.validation.constraints.Size;

/** Finance confirms the deposit's credit landed in the company bank account (manual ops path). */
public record ConfirmBankCreditRequest(
        @Size(max = 80) String bankCreditRef) {
}
