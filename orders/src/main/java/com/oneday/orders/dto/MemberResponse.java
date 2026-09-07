package com.oneday.orders.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.orders.domain.B2bAccountMember;

import java.util.UUID;

/**
 * A row in the account's team list. {@code role} is OWNER or MEMBER; {@code kycStatus} UNVERIFIED or
 * VERIFIED. {@code spendLimitPaise} is the member's monthly budget (null = unlimited). {@code
 * spentThisMonthPaise} / {@code remainingPaise} are populated only where computed (team list, /me);
 * they are omitted from the JSON otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberResponse(UUID userId, String email, String name, String role, String kycStatus,
                             Long spendLimitPaise, Long spentThisMonthPaise, Long remainingPaise) {

    /** Row fields only — no spend computed (used by add/remove/kyc results). */
    public static MemberResponse from(B2bAccountMember m) {
        return new MemberResponse(m.getUserId(), m.getEmail(), m.getName(), m.getRole().name(),
                m.getKycStatus().name(), m.getSpendLimitPaise(), null, null);
    }

    /** Row fields + this-month spend and remaining budget (null remaining when the member is uncapped). */
    public static MemberResponse withSpend(B2bAccountMember m, long spentThisMonthPaise) {
        Long limit = m.getSpendLimitPaise();
        Long remaining = limit == null ? null : Math.max(0L, limit - spentThisMonthPaise);
        return new MemberResponse(m.getUserId(), m.getEmail(), m.getName(), m.getRole().name(),
                m.getKycStatus().name(), limit, spentThisMonthPaise, remaining);
    }
}
