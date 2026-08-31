package com.oneday.orders.repository;

import com.oneday.orders.domain.DaCodBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DaCodBalanceRepository extends JpaRepository<DaCodBalance, UUID> {

    /**
     * Idempotently create the DA's balance row (zero) if it doesn't exist. Called before the locking
     * read so the row is always present to lock; safe under concurrent first-postings (ON CONFLICT).
     */
    @Modifying
    @Query(value = "INSERT INTO da_cod_balance (da_user_id, cash_in_hand_paise) VALUES (:daId, 0) "
            + "ON CONFLICT (da_user_id) DO NOTHING", nativeQuery = true)
    void ensureRow(@Param("daId") UUID daId);

    /** Pessimistic-write lock (SELECT … FOR UPDATE) — serialises concurrent postings for one DA. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM DaCodBalance b WHERE b.daUserId = :daId")
    Optional<DaCodBalance> findByIdForUpdate(@Param("daId") UUID daId);
}
