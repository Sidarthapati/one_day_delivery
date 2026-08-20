package com.oneday.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-side record of the amount a wallet recharge was ORDERED for, keyed by the gateway order id.
 * Confirmation reads the credited amount from here — never from the client — because Razorpay's HMAC
 * signature covers only {@code orderId|paymentId} and does not sign the amount. Append-only.
 */
@Entity
@Table(name = "wallet_recharge_order")
@Getter
@Setter
@NoArgsConstructor
public class WalletRechargeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "razorpay_order_id", length = 80, nullable = false, updatable = false, unique = true)
    private String razorpayOrderId;

    @Column(name = "b2b_account_id", nullable = false, updatable = false)
    private UUID b2bAccountId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private Long amountPaise;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
