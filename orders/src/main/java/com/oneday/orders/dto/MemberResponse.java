package com.oneday.orders.dto;

import com.oneday.orders.domain.B2bAccountMember;

import java.util.UUID;

/** A row in the account's team list. {@code role} is OWNER or MEMBER. */
public record MemberResponse(UUID userId, String email, String name, String role) {

    public static MemberResponse from(B2bAccountMember m) {
        return new MemberResponse(m.getUserId(), m.getEmail(), m.getName(), m.getRole().name());
    }
}
