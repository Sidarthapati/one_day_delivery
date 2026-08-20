package com.oneday.orders.repository;

import com.oneday.orders.domain.WalletRechargeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletRechargeOrderRepository extends JpaRepository<WalletRechargeOrder, UUID> {

    /** The recharge order for this gateway id, scoped to the owning account (defence in depth). */
    Optional<WalletRechargeOrder> findByRazorpayOrderIdAndB2bAccountId(String razorpayOrderId, UUID b2bAccountId);
}
