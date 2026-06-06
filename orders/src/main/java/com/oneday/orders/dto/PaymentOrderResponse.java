package com.oneday.orders.dto;

/**
 * Response for {@code POST /api/v1/payments/order} — everything the checkout UI needs to
 * open against the freshly created gateway order. {@code mock=true} means the test gateway
 * is active (no real charge).
 */
public record PaymentOrderResponse(
        String orderId,
        long amountPaise,
        String currency,
        String keyId,
        boolean mock
) {}
