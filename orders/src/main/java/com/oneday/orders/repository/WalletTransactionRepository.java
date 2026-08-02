package com.oneday.orders.repository;

import com.oneday.orders.domain.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    List<WalletTransaction> findByB2bAccountIdOrderByCreatedAtDesc(UUID b2bAccountId);

    /** Idempotency guard for recharge confirms — a razorpay payment id credits the wallet once. */
    boolean existsByReference(String reference);
}
