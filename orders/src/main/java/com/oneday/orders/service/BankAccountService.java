package com.oneday.orders.service;

import com.oneday.orders.dto.BankAccountRequest;
import com.oneday.orders.dto.BankAccountResponse;

import java.util.UUID;

/** Capture + verify a merchant's payout bank account (the account COD is remitted to). */
public interface BankAccountService {

    /** The account owned by this account id, masked (or the empty state). */
    BankAccountResponse get(UUID accountId);

    /** Submit or replace the bank account; kicks off verification via {@link PayoutPort}. */
    BankAccountResponse submit(UUID accountId, BankAccountRequest request);
}
