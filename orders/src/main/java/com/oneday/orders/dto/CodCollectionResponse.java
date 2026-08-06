package com.oneday.orders.dto;

import java.time.Instant;
import java.util.UUID;

public record CodCollectionResponse(
        UUID id,
        String shipmentRef,
        long amountPaise,
        String state,
        Instant collectedAt,
        UUID remittanceId,
        Instant createdAt
) {}
