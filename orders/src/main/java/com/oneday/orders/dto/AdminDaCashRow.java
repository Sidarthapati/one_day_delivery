package com.oneday.orders.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the station/admin "DA Cash" view: a delivery associate's live COD cash-in-hand (from the
 * append-only ledger) plus who they are and when they last deposited. {@code cashInHandPaise} is the
 * authoritative running balance; {@code lastDepositAt} is null if the DA has never deposited. Name /
 * email / city are best-effort (null if the user record can't be resolved). Snake_case on the wire.
 */
public record AdminDaCashRow(
        UUID daUserId,
        String daName,
        String daEmail,
        String cityId,
        long cashInHandPaise,
        Instant lastDepositAt) {
}
