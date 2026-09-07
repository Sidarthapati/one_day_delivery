package com.oneday.orders.domain;

/**
 * Whether the buyer's cash for one COD collection has actually reached the company bank account.
 * Separate from {@link CodCollectionState} (which tracks the vendor-payout lifecycle): a collection is
 * COLLECTED the moment the DA takes the cash, but only becomes {@link #BANK_SETTLED} once a
 * bank-confirmed deposit by that DA covers it (allocated FIFO). Remittance to the vendor is gated on
 * BANK_SETTLED, so we never pay a vendor before the money is in our account (Discussion-3 ix).
 */
public enum CodCollectionSettlementState {
    /** Cash is with the DA / in transit to the bank — not yet confirmed landed. */
    IN_CUSTODY,
    /** A bank-confirmed deposit has covered this collection's cash. Remittable. */
    BANK_SETTLED
}
