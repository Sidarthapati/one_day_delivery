package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Links an M1 user to a B2B account — the "multiple service accounts" model. One account can have many
 * members; a user belongs to at most one account (unique user_id). The OWNER (the account creator,
 * backfilled from {@code b2b_accounts.owner_user_id}) can invite/remove; a MEMBER just uses the account.
 * {@code email}/{@code name} are denormalised at invite time for a display-only member list.
 */
@Entity
@Table(name = "b2b_account_member")
@Getter
@Setter
@NoArgsConstructor
public class B2bAccountMember extends MutableBaseEntity {

    @Column(name = "b2b_account_id", nullable = false, updatable = false)
    private UUID b2bAccountId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    private MemberRole role = MemberRole.MEMBER;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "name", length = 200)
    private String name;
}
