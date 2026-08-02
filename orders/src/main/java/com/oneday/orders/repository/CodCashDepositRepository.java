package com.oneday.orders.repository;

import com.oneday.orders.domain.CodCashDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CodCashDepositRepository extends JpaRepository<CodCashDeposit, UUID> {

    List<CodCashDeposit> findByDaUserIdOrderByCreatedAtDesc(UUID daUserId);

    List<CodCashDeposit> findAllByOrderByCreatedAtDesc();

    /** Σ of all deposits declared by one DA (across statuses) — what they've handed in. */
    @Query("SELECT COALESCE(SUM(d.amountPaise), 0) FROM CodCashDeposit d WHERE d.daUserId = :daUserId")
    long sumDepositedByDa(@Param("daUserId") UUID daUserId);

    /** Distinct DAs that have declared at least one deposit. */
    @Query("SELECT DISTINCT d.daUserId FROM CodCashDeposit d")
    List<UUID> findDistinctDaIds();
}
