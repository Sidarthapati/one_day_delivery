package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.domain.CodCollectionState;
import com.oneday.orders.dto.CodCollectionResponse;
import com.oneday.orders.dto.CodRemittanceResponse;
import com.oneday.orders.dto.CodSummaryResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.CodRemittanceService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * A vendor's own COD remittance ledger. The account is resolved from the caller (never a request
 * param), so a B2B user only ever sees their own COD. Admin payouts live in {@link AdminCodController}.
 */
@RestController
@RequestMapping("/api/v1/cod")
class CodController {

    private final CodRemittanceService cod;
    private final B2bAccountRepository accounts;

    CodController(CodRemittanceService cod, B2bAccountRepository accounts) {
        this.cod = cod;
        this.accounts = accounts;
    }

    @GetMapping("/summary")
    public CodSummaryResponse summary(@AuthenticationPrincipal AuthUserDetails principal) {
        return cod.summaryFor(ownedAccountId(principal));
    }

    @GetMapping("/collections")
    public List<CodCollectionResponse> collections(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "state", required = false) String state) {
        return cod.collectionsFor(ownedAccountId(principal), parseState(state));
    }

    @GetMapping("/remittances")
    public List<CodRemittanceResponse> remittances(@AuthenticationPrincipal AuthUserDetails principal) {
        return cod.remittancesFor(ownedAccountId(principal));
    }

    @GetMapping("/remittances/{id}")
    public CodRemittanceResponse remittance(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("id") UUID id) {
        UUID accountId = ownedAccountId(principal);
        CodRemittanceResponse r = cod.remittanceDetail(id);
        if (!accountId.equals(r.b2bAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Remittance not found");
        }
        return r;
    }

    /** The account owned by the caller, or 404 (also gates the endpoint to B2B users). */
    private UUID ownedAccountId(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByOwnerUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"))
                .getId();
    }

    private static CodCollectionState parseState(String state) {
        if (state == null || state.isBlank()) return null;
        try {
            return CodCollectionState.valueOf(state.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown COD state: " + state);
        }
    }
}
