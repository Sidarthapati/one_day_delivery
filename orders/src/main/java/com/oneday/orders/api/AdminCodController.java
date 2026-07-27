package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.CodAccountBalanceResponse;
import com.oneday.orders.dto.CodCollectionResponse;
import com.oneday.orders.dto.CodRemittanceResponse;
import com.oneday.orders.dto.CreateRemittanceRequest;
import com.oneday.orders.dto.MarkRemittancePaidRequest;
import com.oneday.orders.domain.CodCollectionState;
import com.oneday.orders.service.CodRemittanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * ADMIN COD payouts: the worklist of vendors with money to remit, batch creation, and marking a
 * batch paid once the bank transfer clears. Read of any account's collections is allowed here too.
 */
@RestController
@RequestMapping("/api/v1/admin/cod")
class AdminCodController {

    private final CodRemittanceService cod;

    AdminCodController(CodRemittanceService cod) {
        this.cod = cod;
    }

    /** Vendors that currently have a payout-available balance, largest first. */
    @GetMapping("/accounts")
    public List<CodAccountBalanceResponse> accounts(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, "ADMIN");
        return cod.accountsWithBalance();
    }

    /** All collections for one account (admin view), optionally filtered by state. */
    @GetMapping("/collections")
    public List<CodCollectionResponse> collections(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam("accountId") UUID accountId,
            @RequestParam(value = "state", required = false) String state) {
        Authz.requireRole(principal, "ADMIN");
        CodCollectionState parsed = (state == null || state.isBlank())
                ? null : CodCollectionState.valueOf(state.trim().toUpperCase());
        return cod.collectionsFor(accountId, parsed);
    }

    /** All remittances (optionally by state, e.g. PENDING) across accounts. */
    @GetMapping("/remittances")
    public List<CodRemittanceResponse> remittances(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "state", required = false) String state) {
        Authz.requireRole(principal, "ADMIN");
        return cod.allRemittances(state);
    }

    @PostMapping("/remittances")
    @ResponseStatus(HttpStatus.CREATED)
    public CodRemittanceResponse create(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody CreateRemittanceRequest request) {
        Authz.requireRole(principal, "ADMIN");
        UUID actorId = UUID.fromString(Authz.requireUserId(principal));
        return cod.createRemittance(request.b2bAccountId(), actorId);
    }

    @PostMapping("/remittances/{id}/pay")
    public CodRemittanceResponse pay(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody MarkRemittancePaidRequest request) {
        Authz.requireRole(principal, "ADMIN");
        return cod.markPaid(id, request.utr());
    }
}
