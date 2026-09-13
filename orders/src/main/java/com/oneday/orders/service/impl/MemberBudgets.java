package com.oneday.orders.service.impl;

import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.B2bAccountMember;

/**
 * Resolves a member's <em>effective</em> monthly spend cap in paise (Discussion-4 M1). A member is one
 * of three states: unlimited (both limit fields null), a fixed paise cap, or a percent of the account's
 * available credit ({@code credit_limit - outstanding}). One place so the booking gate and the console
 * display agree.
 */
final class MemberBudgets {

    private MemberBudgets() {}

    /** The member's cap in paise, or {@code null} when uncapped (unlimited, or an unknown state). */
    static Long effectiveCapPaise(B2bAccountMember member, B2bAccount account) {
        if (member == null) {
            return null;
        }
        Integer pct = member.getSpendLimitPct();
        if (pct != null) {
            long limit = account.getCreditLimitPaise() == null ? 0L : account.getCreditLimitPaise();
            long outstanding = account.getOutstandingBalancePaise() == null ? 0L : account.getOutstandingBalancePaise();
            long available = Math.max(0L, limit - outstanding);
            return available * pct / 100;
        }
        return member.getSpendLimitPaise();   // fixed, or null = unlimited
    }
}
