package com.oneday.orders.domain;

/**
 * A movement on a B2B prepaid wallet. Signed amount convention: RECHARGE / REFUND /
 * REMITTANCE_CREDIT credit the wallet (+), DEBIT debits it (−), ADJUSTMENT is a manual correction
 * either way. Every movement is an append-only {@link WalletTransaction} row.
 */
public enum WalletTransactionType {
    RECHARGE,
    DEBIT,
    REFUND,
    REMITTANCE_CREDIT,
    ADJUSTMENT
}
