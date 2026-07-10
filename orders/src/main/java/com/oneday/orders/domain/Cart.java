package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.orders.domain.enums.CartStatus;
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
 * A user's shipment cart. At most one {@link CartStatus#OPEN} cart exists per user (enforced by
 * a partial unique index). Aggregate B2C payment refs are recorded at checkout for audit.
 */
@Entity
@Table(name = "cart")
@Getter
@Setter
@NoArgsConstructor
public class Cart extends MutableBaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private CartStatus status = CartStatus.OPEN;

    @Column(name = "checkout_razorpay_order_id", length = 100)
    private String checkoutRazorpayOrderId;

    @Column(name = "checkout_razorpay_payment_id", length = 100)
    private String checkoutRazorpayPaymentId;

    @Column(name = "checkout_total_paise")
    private Long checkoutTotalPaise;
}
