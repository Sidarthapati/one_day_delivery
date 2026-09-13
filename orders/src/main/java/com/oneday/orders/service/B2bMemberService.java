package com.oneday.orders.service;

import com.oneday.orders.dto.MemberResponse;

import java.util.List;
import java.util.UUID;

/**
 * Team management for a B2B account (the "multiple service accounts" feature). Membership resolution is
 * in {@code B2bAccountRepository.findByMemberUserId}; this manages who is on the account. Mutations are
 * OWNER-only; listing is open to any member.
 */
public interface B2bMemberService {

    /** Everyone on the account, owner first. */
    List<MemberResponse> list(UUID accountId);

    /** The caller's own member row (incl. KYC status). 404 if they aren't a member of this account. */
    MemberResponse me(UUID accountId, UUID callerUserId);

    /**
     * Add an existing business user (looked up by email) to the account as a MEMBER, with an optional
     * initial budget (D4 M1). OWNER-only. 404 if no such user, 422 if they're not a business user or the
     * budget is malformed, 409 if they already belong to an account. Both budget args null ⇒ unlimited.
     */
    MemberResponse add(UUID accountId, UUID callerUserId, String email,
                       Long spendLimitPaise, Integer spendLimitPct);

    /** Remove a member. OWNER-only; the OWNER cannot be removed. */
    void remove(UUID accountId, UUID callerUserId, UUID targetUserId);

    /**
     * Set a member's monthly budget (D4 M1). Exactly one of: both null (unlimited), {@code spendLimitPaise}
     * (fixed), {@code spendLimitPct} (a percent of available credit). Setting both is 422. OWNER-only; the
     * owner is exempt and cannot be capped. Returns the target's updated row with the resolved cap + spend.
     */
    MemberResponse setBudget(UUID accountId, UUID callerUserId, UUID targetUserId,
                             Long spendLimitPaise, Integer spendLimitPct);

    /**
     * The caller verifies their own KYC by PAN (Discussion-2 xii). On a verified PAN whose name matches,
     * the caller's membership flips to VERIFIED. 404 if the caller isn't a member; 422 if the PAN fails
     * or the name doesn't match. Returns the caller's updated member row.
     */
    MemberResponse verifyMyKyc(UUID accountId, UUID callerUserId, String pan, String name);
}
