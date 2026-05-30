package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.orders.domain.enums.PaymentStatus;
import com.oneday.orders.domain.enums.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
@NoArgsConstructor
public class PaymentTransaction extends MutableBaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(name = "razorpay_order_id", length = 100, nullable = false, unique = true, updatable = false)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 100)
    private String razorpayPaymentId;

    @Column(name = "razorpay_signature", length = 500)
    private String razorpaySignature;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private Long amountPaise;

    @Column(name = "tax_paise", nullable = false)
    private Long taxPaise;

    @Column(name = "total_paise", nullable = false, updatable = false)
    private Long totalPaise;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private PaymentStatus status;

    @Column(name = "refund_id", length = 100)
    private String refundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", length = 20)
    private RefundStatus refundStatus;

    @Column(name = "refund_amount_paise")
    private Long refundAmountPaise;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;
}
