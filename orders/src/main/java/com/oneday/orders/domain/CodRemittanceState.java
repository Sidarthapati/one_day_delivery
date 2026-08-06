package com.oneday.orders.domain;

/** Lifecycle of a COD remittance (payout batch to one vendor). */
public enum CodRemittanceState {
    /** Batched; awaiting the bank transfer. */
    PENDING,
    /** Transfer confirmed (UTR recorded). */
    PAID,
    /** Transfer failed / reversed; collections are released back to the pool. */
    FAILED
}
