package com.oneday.orders.dto;

import java.util.UUID;

/**
 * One row of the admin cash-reconciliation view: per delivery associate, the COD cash they were
 * expected to collect vs what they've handed in. {@code variancePaise} = collected − deposited
 * (positive ⇒ the DA still owes cash; negative ⇒ over-deposited, needs a look). {@code cashInHandPaise}
 * is the authoritative running balance from the append-only ledger (issue #191 — the variance view and
 * the ledger read the same source; the two agree once opening balances are backfilled, see #185). Name
 * / email are best-effort (null if the user record can't be resolved). Snake_case on the wire.
 */
public record AdminCodReconciliationRow(
        UUID daUserId,
        String daName,
        String daEmail,
        long collectedCount,
        long collectedPaise,
        long depositedPaise,
        long variancePaise,
        long cashInHandPaise) {
}
