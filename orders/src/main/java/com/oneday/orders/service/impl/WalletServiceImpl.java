package com.oneday.orders.service.impl;

import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.WalletTransaction;
import com.oneday.orders.domain.WalletTransactionType;
import com.oneday.orders.dto.WalletResponse;
import com.oneday.orders.dto.WalletTransactionResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.WalletTransactionRepository;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
class WalletServiceImpl implements WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletServiceImpl.class);

    private final B2bAccountRepository accounts;
    private final WalletTransactionRepository ledger;
    private final PaymentPort paymentPort;

    WalletServiceImpl(B2bAccountRepository accounts,
                      WalletTransactionRepository ledger,
                      PaymentPort paymentPort) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.paymentPort = paymentPort;
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse balanceFor(UUID accountId) {
        B2bAccount a = require(accountId);
        return WalletResponse.of(accountId, balance(a));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletTransactionResponse> ledgerFor(UUID accountId) {
        return ledger.findByB2bAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(WalletTransactionResponse::from)
                .toList();
    }

    @Override
    public PaymentPort.PaymentOrder createRechargeOrder(UUID accountId, long amountPaise) {
        require(accountId);
        // Razorpay caps receipt at 40 chars; "wr-" + 32-hex account id = 35.
        return paymentPort.createOrder(amountPaise, "wr-" + accountId.toString().replace("-", ""));
    }

    @Override
    @Transactional
    public WalletResponse confirmRecharge(UUID accountId, String razorpayOrderId, String razorpayPaymentId,
                                          String signature, long amountPaise) {
        B2bAccount a = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        // Idempotent: a payment id credits the wallet exactly once.
        if (ledger.existsByReference(razorpayPaymentId)) {
            log.info("Wallet recharge for payment {} already applied; returning current balance", razorpayPaymentId);
            return WalletResponse.of(accountId, balance(a));
        }
        // A tampered signature throws PaymentVerificationException → mapped to 402 by the handler.
        paymentPort.verifySignature(razorpayOrderId, razorpayPaymentId, signature);
        paymentPort.capture(razorpayPaymentId, amountPaise);

        long newBalance = balance(a) + amountPaise;
        a.setWalletBalancePaise(newBalance);
        accounts.save(a);
        record(accountId, WalletTransactionType.RECHARGE, amountPaise, newBalance,
                razorpayPaymentId, "Wallet recharge", null);
        log.info("Wallet recharged: account {} +{} paise → {} (payment {})",
                accountId, amountPaise, newBalance, razorpayPaymentId);
        return WalletResponse.of(accountId, newBalance);
    }

    @Override
    public void debitForBooking(B2bAccount account, long amountPaise, String shipmentRef, UUID actorId) {
        long current = balance(account);
        if (amountPaise > current) {
            throw new InsufficientWalletBalanceException(
                    "Wallet balance " + current + " paise cannot cover booking " + amountPaise + " paise");
        }
        long newBalance = current - amountPaise;
        account.setWalletBalancePaise(newBalance);
        accounts.save(account);
        record(account.getId(), WalletTransactionType.DEBIT, -amountPaise, newBalance,
                shipmentRef, "Shipment " + shipmentRef, actorId);
        log.info("Wallet debited: account {} -{} paise → {} (shipment {})",
                account.getId(), amountPaise, newBalance, shipmentRef);
    }

    @Override
    public void refundForCancellation(B2bAccount account, long amountPaise, String shipmentRef, UUID actorId) {
        long newBalance = balance(account) + amountPaise;
        account.setWalletBalancePaise(newBalance);
        accounts.save(account);
        record(account.getId(), WalletTransactionType.REFUND, amountPaise, newBalance,
                shipmentRef, "Refund — cancelled " + shipmentRef, actorId);
        log.info("Wallet refunded: account {} +{} paise → {} (cancelled {})",
                account.getId(), amountPaise, newBalance, shipmentRef);
    }

    @Override
    @Transactional
    public WalletResponse mockCredit(UUID accountId, long amountPaise) {
        B2bAccount a = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        long newBalance = balance(a) + amountPaise;
        a.setWalletBalancePaise(newBalance);
        accounts.save(a);
        record(accountId, WalletTransactionType.RECHARGE, amountPaise, newBalance,
                "mock-" + UUID.randomUUID(), "Wallet recharge (dev mock)", null);
        return WalletResponse.of(accountId, newBalance);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void record(UUID accountId, WalletTransactionType type, long amountPaise, long balanceAfter,
                        String reference, String description, UUID createdBy) {
        WalletTransaction t = new WalletTransaction();
        t.setB2bAccountId(accountId);
        t.setType(type);
        t.setAmountPaise(amountPaise);
        t.setBalanceAfterPaise(balanceAfter);
        t.setReference(reference);
        t.setDescription(description);
        t.setCreatedBy(createdBy);
        ledger.save(t);
    }

    private B2bAccount require(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private static long balance(B2bAccount a) {
        Long b = a.getWalletBalancePaise();
        return b == null ? 0L : b;
    }
}
