package com.oneday.orders.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Invite an existing Godspeed business user to the caller's account (as a MEMBER), optionally with an
 * initial budget (D4 M1). Both budget fields null ⇒ unlimited; set at most one (fixed or percentage).
 */
public record AddMemberRequest(
        @NotBlank @Email String email,
        @PositiveOrZero Long spendLimitPaise,
        @Min(1) @Max(100) Integer spendLimitPct) {
}
