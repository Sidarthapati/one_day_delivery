package com.oneday.orders.repository;

import com.oneday.orders.domain.CashHandoffOtp;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** One active handoff OTP per deposit (mirrors {@code PickupOtpRepository}). */
public interface CashHandoffOtpRepository extends JpaRepository<CashHandoffOtp, UUID> {

    Optional<CashHandoffOtp> findByDepositId(UUID depositId);

    /**
     * The active OTP for a deposit, locked FOR UPDATE — used in verify() so two concurrent verifies
     * can't both see {@code used=false}. Caller must be {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM CashHandoffOtp o WHERE o.depositId = :depositId")
    Optional<CashHandoffOtp> findByDepositIdWithLock(@Param("depositId") UUID depositId);

    @Transactional
    @Modifying
    @Query("DELETE FROM CashHandoffOtp o WHERE o.depositId = :depositId")
    void deleteByDepositId(@Param("depositId") UUID depositId);
}
