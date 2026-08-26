package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.AddMemberRequest;
import com.oneday.orders.dto.MemberResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.B2bMemberService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Team management for the caller's B2B account (the "multiple service accounts" feature). Owner-scoped:
 * the account is resolved from the caller (a member), never a param. Listing is open to any member;
 * adding/removing is enforced OWNER-only in the service.
 */
@RestController
@RequestMapping("/api/v1/b2b/members")
class MembersController {

    private final B2bMemberService members;
    private final B2bAccountRepository accounts;

    MembersController(B2bMemberService members, B2bAccountRepository accounts) {
        this.members = members;
        this.accounts = accounts;
    }

    /** Everyone on the caller's account, owner first. */
    @GetMapping
    public List<MemberResponse> list(@AuthenticationPrincipal AuthUserDetails principal) {
        return members.list(ownedAccountId(principal));
    }

    /** Invite an existing business user (by email) to the caller's account. OWNER-only. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse add(@AuthenticationPrincipal AuthUserDetails principal,
                              @Valid @RequestBody AddMemberRequest request) {
        UUID caller = UUID.fromString(Authz.requireUserId(principal));
        return members.add(ownedAccountId(principal), caller, request.email());
    }

    /** Remove a member from the caller's account. OWNER-only; the owner can't be removed. */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AuthUserDetails principal, @PathVariable UUID userId) {
        UUID caller = UUID.fromString(Authz.requireUserId(principal));
        members.remove(ownedAccountId(principal), caller, userId);
    }

    /** The B2B account the caller belongs to, or 404 (also gates the endpoint to B2B users). */
    private UUID ownedAccountId(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByMemberUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"))
                .getId();
    }
}
