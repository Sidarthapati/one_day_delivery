package com.oneday.orders.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.orders.domain.B2bAccountMember;

import java.util.UUID;

/**
 * A row in the account's team list. Budget (D3 vi + D4 M1): a member is <b>unlimited</b> (both limit
 * fields null), <b>fixed</b> ({@code spendLimitPaise}), or <b>percentage</b> ({@code spendLimitPct} of
 * available credit). {@code effectiveLimitPaise} is the resolved monthly cap (fixed value, or the %
 * applied to current available credit); {@code spentThisMonthPaise} / {@code remainingPaise} are
 * populated only where computed (team list, /me) and omitted from JSON otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberResponse(UUID userId, String email, String name, String role, String kycStatus,
                             Long spendLimitPaise, Integer spendLimitPct, Long effectiveLimitPaise,
                             Long spentThisMonthPaise, Long remainingPaise) {

    /** Row fields only — no account resolution (used by mutation results; the client refetches the list). */
    public static MemberResponse from(B2bAccountMember m) {
        return new MemberResponse(m.getUserId(), m.getEmail(), m.getName(), m.getRole().name(),
                m.getKycStatus().name(), m.getSpendLimitPaise(), m.getSpendLimitPct(), null, null, null);
    }

    /**
     * Row fields + the resolved effective cap, this-month spend, and remaining budget.
     * {@code effectiveCapPaise} null ⇒ unlimited (remaining stays null).
     */
    public static MemberResponse withBudget(B2bAccountMember m, Long effectiveCapPaise, long spentThisMonthPaise) {
        Long remaining = effectiveCapPaise == null ? null : Math.max(0L, effectiveCapPaise - spentThisMonthPaise);
        Long spent = effectiveCapPaise == null ? null : spentThisMonthPaise;
        return new MemberResponse(m.getUserId(), m.getEmail(), m.getName(), m.getRole().name(),
                m.getKycStatus().name(), m.getSpendLimitPaise(), m.getSpendLimitPct(),
                effectiveCapPaise, spent, remaining);
    }
}
