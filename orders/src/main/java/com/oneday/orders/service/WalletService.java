package com.oneday.orders.service;

import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.dto.WalletResponse;
import com.oneday.orders.dto.WalletTransactionResponse;

import java.util.List;
import java.util.UUID;

/**
 * A B2B account's prepaid wallet: recharge (money in via the payment gateway) and debit (money out
 * when a wallet-funded shipment is booked). The running balance lives on
 * {@code b2b_accounts.wallet_balance_paise}; every movement is an append-only ledger row.
 */
public interface WalletService {

    /** Current balance for the account. */
    WalletResponse balanceFor(UUID accountId);

    /** The account's wallet ledger, newest first. */
    List<WalletTransactionResponse> ledgerFor(UUID accountId);

    /** Mint a gateway order to recharge the wallet by {@code amountPaise}. */
    com.oneday.orders.service.PaymentPort.PaymentOrder createRechargeOrder(UUID accountId, long amountPaise);

    /**
     * Verify the gateway signature and credit the wallet by the amount recorded on the server-side
     * recharge order (never a client-supplied amount — Razorpay does not sign the amount). Idempotent
     * on the razorpay payment id — a duplicate confirm returns the current balance without double-crediting.
     */
    WalletResponse confirmRecharge(UUID accountId, String razorpayOrderId, String razorpayPaymentId,
                                   String signature);

    /**
     * Debit the wallet for a booking. Called INSIDE the booking transaction with a row already
     * locked via {@code findByIdForUpdate}; throws {@link InsufficientWalletBalanceException} (→409)
     * when the balance can't cover the amount. Mutates {@code account} + appends a DEBIT row.
     */
    void debitForBooking(B2bAccount account, long amountPaise, String shipmentRef, UUID actorId);

    /** Refund a wallet-funded booking (e.g. on cancellation). Credits the wallet + appends a REFUND row. */
    void refundForCancellation(B2bAccount account, long amountPaise, String shipmentRef, UUID actorId);

    /** Dev-only: credit the wallet without a real gateway payment (mirrors MockPaymentController). */
    WalletResponse mockCredit(UUID accountId, long amountPaise);

    /** Raised when a wallet debit would take the balance negative. */
    class InsufficientWalletBalanceException extends RuntimeException {
        public InsufficientWalletBalanceException(String message) { super(message); }
    }
}
