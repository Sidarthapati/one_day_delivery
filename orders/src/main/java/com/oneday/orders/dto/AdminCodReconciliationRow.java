package com.oneday.orders.dto;

import java.util.UUID;

/**
 * One row of the admin cash-reconciliation view: per delivery associate, the COD cash they were
 * expected to collect vs what they've handed in. {@code variancePaise} = collected − deposited
 * (positive ⇒ the DA still owes cash; negative ⇒ over-deposited, needs a look).
 */
public record AdminCodReconciliationRow(
        UUID daUserId,
        long collectedCount,
        long collectedPaise,
        long depositedPaise,
        long variancePaise) {
}
