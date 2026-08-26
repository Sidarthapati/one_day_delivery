package com.oneday.orders.repository;

import com.oneday.orders.domain.B2bAccountMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface B2bAccountMemberRepository extends JpaRepository<B2bAccountMember, UUID> {

    /** Members of one account, oldest first (the owner, backfilled first, sorts to the top). */
    List<B2bAccountMember> findByB2bAccountIdOrderByCreatedAtAsc(UUID b2bAccountId);

    /** The caller's membership in a specific account (for the owner-only guard). */
    Optional<B2bAccountMember> findByB2bAccountIdAndUserId(UUID b2bAccountId, UUID userId);

    /** True if this user already belongs to any account (a user is on at most one account in v1). */
    boolean existsByUserId(UUID userId);
}
