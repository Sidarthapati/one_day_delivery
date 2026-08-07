package com.oneday.orders.dto;

import java.util.UUID;

/** A B2B account's current wallet balance. */
public record WalletResponse(UUID b2bAccountId, long balancePaise, String currency) {

    public static WalletResponse of(UUID accountId, long balancePaise) {
        return new WalletResponse(accountId, balancePaise, "INR");
    }
}
