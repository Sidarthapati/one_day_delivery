package com.oneday.orders.repository;

import com.oneday.orders.domain.B2bAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface B2bAccountRepository extends JpaRepository<B2bAccount, UUID> {

    List<B2bAccount> findByCityId(String cityId);

    List<B2bAccount> findByIsActive(boolean isActive);

    Optional<B2bAccount> findByBillingEmail(String billingEmail);

    Optional<B2bAccount> findByOwnerUserId(UUID ownerUserId);

    /**
     * The account a user can act on, resolved via membership (owners are members too, backfilled by
     * V4_44) — so an invited teammate resolves to the same account as the owner. A user is normally on
     * one account; if the DB has more (the seeded demo principal owns both demo accounts), this resolves
     * deterministically to the earliest-joined one rather than erroring on a non-unique result.
     */
    default Optional<B2bAccount> findByMemberUserId(UUID userId) {
        return findAccountsForMember(userId).stream().findFirst();
    }

    @Query("SELECT a FROM B2bAccount a, B2bAccountMember m WHERE m.b2bAccountId = a.id AND m.userId = :userId "
            + "ORDER BY m.createdAt ASC, a.id ASC")
    List<B2bAccount> findAccountsForMember(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM B2bAccount a WHERE a.id = :id")
    Optional<B2bAccount> findByIdForUpdate(@Param("id") UUID id);
}
