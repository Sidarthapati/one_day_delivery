package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.WalletRechargeOrderRequest;
import com.oneday.orders.dto.WalletResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Dev-only wallet top-up (no gateway round-trip), for seeding a balance quickly on staging/demo.
 * Never present in prod, where recharge always goes through the real gateway confirm.
 */
@RestController
@RequestMapping("/api/v1/wallet/mock")
@Profile("!prod")
class MockWalletController {

    private final WalletService wallet;
    private final B2bAccountRepository accounts;

    MockWalletController(WalletService wallet, B2bAccountRepository accounts) {
        this.wallet = wallet;
        this.accounts = accounts;
    }

    @PostMapping("/recharge")
    public WalletResponse mockRecharge(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody WalletRechargeOrderRequest req) {
        return wallet.mockCredit(ownedAccountId(principal), req.getAmountPaise());
    }

    private UUID ownedAccountId(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByMemberUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"))
                .getId();
    }
}
