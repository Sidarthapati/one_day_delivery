package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.DaCodCashSummaryResponse;
import com.oneday.orders.dto.DaCodLedgerEntryResponse;
import com.oneday.orders.dto.RecordCodDepositRequest;
import com.oneday.orders.service.CodCashService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A delivery associate's own COD cash: declare a deposit and see their collected-vs-deposited
 * position. The DA is resolved from the caller (userId == daId), so a rider only ever sees/acts on
 * their own cash. Consumed by the driver app (scheduled). Admin reconciliation is in
 * {@link AdminCodController}.
 */
@RestController
@RequestMapping("/api/v1/cod/da")
class DaCodController {

    private final CodCashService codCash;

    DaCodController(CodCashService codCash) {
        this.codCash = codCash;
    }

    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public CodCashDepositResponse recordDeposit(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody RecordCodDepositRequest request) {
        return codCash.recordDeposit(callerDaId(principal), request);
    }

    @GetMapping("/summary")
    public DaCodCashSummaryResponse summary(@AuthenticationPrincipal AuthUserDetails principal) {
        return codCash.daSummary(callerDaId(principal));
    }

    /** The DA's own cash-in-hand ledger (collections + deposits with a running balance), newest first. */
    @GetMapping("/ledger")
    public List<DaCodLedgerEntryResponse> ledger(@AuthenticationPrincipal AuthUserDetails principal) {
        return codCash.daLedger(callerDaId(principal));
    }

    /** The calling delivery associate's own user id (also gates the endpoint to that role). */
    private UUID callerDaId(AuthUserDetails principal) {
        Authz.requireRole(principal, "DELIVERY_ASSOCIATE");
        return UUID.fromString(Authz.requireUserId(principal));
    }
}
