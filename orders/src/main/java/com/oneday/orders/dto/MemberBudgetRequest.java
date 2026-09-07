package com.oneday.orders.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * The owner sets (or clears) a member's monthly spend budget. A null {@code spendLimitPaise} clears the
 * cap (unlimited); a value caps the member's bookings for the current calendar month.
 */
public record MemberBudgetRequest(
        @PositiveOrZero Long spendLimitPaise) {
}
