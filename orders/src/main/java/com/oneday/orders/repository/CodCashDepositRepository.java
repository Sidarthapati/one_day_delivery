package com.oneday.orders.repository;

import com.oneday.orders.domain.CodCashDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodCashDepositRepository extends JpaRepository<CodCashDeposit, UUID> {

    List<CodCashDeposit> findByDaUserIdOrderByCreatedAtDesc(UUID daUserId);

    /** Idempotency guard: a (DA, deposit_ref) pair records a cash drop at most once. */
    Optional<CodCashDeposit> findByDaUserIdAndDepositRef(UUID daUserId, String depositRef);

    List<CodCashDeposit> findAllByOrderByCreatedAtDesc();

    /** Σ of all deposits declared by one DA (across statuses) — what they've handed in. */
    @Query("SELECT COALESCE(SUM(d.amountPaise), 0) FROM CodCashDeposit d WHERE d.daUserId = :daUserId")
    long sumDepositedByDa(@Param("daUserId") UUID daUserId);

    /** Distinct DAs that have declared at least one deposit. */
    @Query("SELECT DISTINCT d.daUserId FROM CodCashDeposit d")
    List<UUID> findDistinctDaIds();

    /** When the DA last handed cash in — null if never (for the station DA-cash worklist, #191). */
    @Query("SELECT MAX(d.createdAt) FROM CodCashDeposit d WHERE d.daUserId = :daUserId")
    java.time.Instant lastDepositAt(@Param("daUserId") UUID daUserId);
}
