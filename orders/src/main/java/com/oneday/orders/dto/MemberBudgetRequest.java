package com.oneday.orders.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The owner sets a member's monthly budget (Discussion-4 M1). Exactly one shape:
 * <ul>
 *   <li><b>unlimited</b> — both fields null;</li>
 *   <li><b>fixed</b> — {@code spendLimitPaise} set;</li>
 *   <li><b>percentage</b> — {@code spendLimitPct} set (1..100, of the account's available credit).</li>
 * </ul>
 * Setting both is rejected (422) by the service.
 */
public record MemberBudgetRequest(
        @PositiveOrZero Long spendLimitPaise,
        @Min(1) @Max(100) Integer spendLimitPct) {
}
