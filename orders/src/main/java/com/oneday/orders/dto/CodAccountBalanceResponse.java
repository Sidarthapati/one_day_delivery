package com.oneday.orders.dto;

import java.util.UUID;

/** Admin payout worklist row: a vendor with money ready to remit. */
public record CodAccountBalanceResponse(
        UUID b2bAccountId,
        String accountName,
        long availableToRemitPaise,
        int availableToRemitCount
) {}
