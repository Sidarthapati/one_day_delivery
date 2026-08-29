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
     * Add an existing business user (looked up by email) to the account as a MEMBER. OWNER-only.
     * 404 if no such user, 422 if they're not a business user, 409 if they already belong to an account.
     */
    MemberResponse add(UUID accountId, UUID callerUserId, String email);

    /** Remove a member. OWNER-only; the OWNER cannot be removed. */
    void remove(UUID accountId, UUID callerUserId, UUID targetUserId);

    /**
     * The caller verifies their own KYC by PAN (Discussion-2 xii). On a verified PAN whose name matches,
     * the caller's membership flips to VERIFIED. 404 if the caller isn't a member; 422 if the PAN fails
     * or the name doesn't match. Returns the caller's updated member row.
     */
    MemberResponse verifyMyKyc(UUID accountId, UUID callerUserId, String pan, String name);
}
