package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.config.RazorpayProperties;
import com.oneday.orders.dto.PaymentOrderResponse;
import com.oneday.orders.dto.WalletRechargeConfirmRequest;
import com.oneday.orders.dto.WalletRechargeOrderRequest;
import com.oneday.orders.dto.WalletResponse;
import com.oneday.orders.dto.WalletTransactionResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * A vendor's prepaid wallet: balance, ledger, and recharge (gateway order → confirm). The account
 * is resolved from the caller (never a param), like {@link CodController}.
 */
@RestController
@RequestMapping("/api/v1/wallet")
class WalletController {

    private final WalletService wallet;
    private final B2bAccountRepository accounts;
    private final RazorpayProperties razorpayProps;

    WalletController(WalletService wallet, B2bAccountRepository accounts, RazorpayProperties razorpayProps) {
        this.wallet = wallet;
        this.accounts = accounts;
        this.razorpayProps = razorpayProps;
    }

    @GetMapping
    public WalletResponse balance(@AuthenticationPrincipal AuthUserDetails principal) {
        return wallet.balanceFor(ownedAccountId(principal));
    }

    @GetMapping("/transactions")
    public List<WalletTransactionResponse> transactions(@AuthenticationPrincipal AuthUserDetails principal) {
        return wallet.ledgerFor(ownedAccountId(principal));
    }

    @PostMapping("/recharge/order")
    public PaymentOrderResponse rechargeOrder(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody WalletRechargeOrderRequest req) {
        PaymentPort.PaymentOrder order = wallet.createRechargeOrder(ownedAccountId(principal), req.getAmountPaise());
        return new PaymentOrderResponse(order.orderId(), order.amountPaise(), order.currency(),
                order.keyId(), !razorpayProps.isLive());
    }

    @PostMapping("/recharge/confirm")
    public WalletResponse rechargeConfirm(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody WalletRechargeConfirmRequest req) {
        return wallet.confirmRecharge(ownedAccountId(principal), req.getRazorpayOrderId(),
                req.getRazorpayPaymentId(), req.getSignature());
    }

    /** The B2B account owned by the caller, or 404 (also gates the endpoint to B2B users). */
    private UUID ownedAccountId(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByOwnerUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"))
                .getId();
    }
}
