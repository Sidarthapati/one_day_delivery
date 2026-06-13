package com.oneday.orders.dto;

import java.util.List;
import java.util.UUID;

/** The user's active cart with its items and a rolled-up total of the cached quotes. */
public record CartResponse(
        UUID cartId,
        String status,
        int itemCount,
        long cartTotalPaise,
        List<CartItemResponse> items
) {}
