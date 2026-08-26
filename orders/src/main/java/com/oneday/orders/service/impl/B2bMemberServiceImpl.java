package com.oneday.orders.service.impl;

import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.service.UserService;
import com.oneday.orders.domain.B2bAccountMember;
import com.oneday.orders.domain.MemberRole;
import com.oneday.orders.dto.MemberResponse;
import com.oneday.orders.repository.B2bAccountMemberRepository;
import com.oneday.orders.service.B2bMemberService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
class B2bMemberServiceImpl implements B2bMemberService {

    private static final String B2B_USER = "B2B_USER";

    private final B2bAccountMemberRepository members;
    private final UserService userService;

    B2bMemberServiceImpl(B2bAccountMemberRepository members, UserService userService) {
        this.members = members;
        this.userService = userService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberResponse> list(UUID accountId) {
        return members.findByB2bAccountIdOrderByCreatedAtAsc(accountId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public MemberResponse add(UUID accountId, UUID callerUserId, String email) {
        requireOwner(accountId, callerUserId);

        UserResponse user;
        try {
            user = userService.getUserByEmail(email.trim());
        } catch (UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No Godspeed user with that email — ask them to sign up first.");
        }
        // v1: only an existing business user can join (we don't elevate a customer's M1 role here).
        if (!B2B_USER.equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "That user isn't a business user, so they can't join a business account yet.");
        }
        if (members.existsByUserId(user.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That user already belongs to a business account.");
        }

        B2bAccountMember m = new B2bAccountMember();
        m.setB2bAccountId(accountId);
        m.setUserId(user.id());
        m.setRole(MemberRole.MEMBER);
        m.setEmail(user.email());
        m.setName(user.name());
        try {
            return MemberResponse.from(members.save(m));
        } catch (DataIntegrityViolationException e) {
            // Lost a race on the unique(user_id) constraint.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That user already belongs to a business account.");
        }
    }

    @Override
    @Transactional
    public void remove(UUID accountId, UUID callerUserId, UUID targetUserId) {
        requireOwner(accountId, callerUserId);
        B2bAccountMember target = members.findByB2bAccountIdAndUserId(accountId, targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a member of this account"));
        if (target.getRole() == MemberRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The account owner can't be removed.");
        }
        members.delete(target);
    }

    /** The caller must be the account's OWNER to manage membership. */
    private void requireOwner(UUID accountId, UUID callerUserId) {
        B2bAccountMember me = members.findByB2bAccountIdAndUserId(accountId, callerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this account"));
        if (me.getRole() != MemberRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the account owner can manage the team.");
        }
    }
}
