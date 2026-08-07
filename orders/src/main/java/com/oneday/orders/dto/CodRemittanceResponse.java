package com.oneday.orders.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A remittance; {@code collections} is populated only in the single-remittance detail view. */
public record CodRemittanceResponse(
        UUID id,
        String reference,
        UUID b2bAccountId,
        long grossPaise,
        long feePaise,
        long netPaise,
        int collectionCount,
        String state,
        String utr,
        Instant periodStart,
        Instant periodEnd,
        String notes,
        Instant createdAt,
        Instant paidAt,
        List<CodCollectionResponse> collections
) {}
