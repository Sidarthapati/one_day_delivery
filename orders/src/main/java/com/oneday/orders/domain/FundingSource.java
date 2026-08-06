package com.oneday.orders.domain;

/**
 * How a B2B booking is paid for.
 *
 * <p>CREDIT draws down the account's credit limit (the outstanding-balance model). WALLET debits
 * the prepaid wallet balance (recharge-then-ship). A booking with no explicit source defaults to
 * {@code creditLimit > 0 ? CREDIT : WALLET} — approved credit accounts keep shipping on credit,
 * everyone else recharges first.
 */
public enum FundingSource {
    CREDIT,
    WALLET
}
