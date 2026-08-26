package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.AdminOrderQueryService;
import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.CancellationService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/b2b/shipments")
class B2bShipmentController {

    private final B2bBookingService b2bBookingService;
    private final CancellationService cancellationService;
    private final AdminOrderQueryService orderQueryService;
    private final B2bAccountRepository accounts;

    B2bShipmentController(B2bBookingService b2bBookingService, CancellationService cancellationService,
                          AdminOrderQueryService orderQueryService, B2bAccountRepository accounts) {
        this.b2bBookingService = b2bBookingService;
        this.cancellationService = cancellationService;
        this.orderQueryService = orderQueryService;
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createShipment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody B2bBookingRequest request) {
        // Only B2B accounts may book on credit. ADMIN is deliberately NOT allowed to book —
        // it has read access to the orders database instead. The service additionally checks
        // the caller owns the specific b2b_account_id.
        Authz.requireCustomerRole(principal, "B2B_USER");
        return b2bBookingService.book(request, idempotencyKey, Authz.requireUserId(principal));
    }

    @DeleteMapping("/{ref}")
    @ResponseStatus(HttpStatus.OK)
    public CancellationResponse cancelShipment(
            @PathVariable("ref") String ref,
            @RequestParam(value = "reason", required = false) String reason,
            @AuthenticationPrincipal AuthUserDetails principal) {
        // Role-gated to B2B; the service additionally enforces account ownership and
        // reverses the credit (outstanding balance) instead of issuing a Razorpay refund.
        Authz.requireRole(principal, "B2B_USER");
        return cancellationService.cancel(ref, reason, Authz.requireUserId(principal), true);
    }

    /**
     * Merchant self-service export: all of the caller's own account's shipments as CSV. Owner-scoped
     * (never a param — resolved from the caller), reusing the ops CSV layout {@link AdminOrdersController#toCsv}.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<Resource> exportMyShipments(@AuthenticationPrincipal AuthUserDetails principal) {
        UUID accountId = ownedAccountId(principal);
        byte[] csv = AdminOrdersController.toCsv(orderQueryService.exportForAccount(accountId))
                .getBytes(StandardCharsets.UTF_8);
        String filename = "my-shipments-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(new ByteArrayResource(csv));
    }

    /** The B2B account owned by the caller, or 404 (also gates the endpoint to B2B users). */
    private UUID ownedAccountId(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByMemberUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"))
                .getId();
    }
}
