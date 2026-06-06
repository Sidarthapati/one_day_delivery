package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.CancellationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/b2b/shipments")
class B2bShipmentController {

    private final B2bBookingService b2bBookingService;
    private final CancellationService cancellationService;

    B2bShipmentController(B2bBookingService b2bBookingService, CancellationService cancellationService) {
        this.b2bBookingService = b2bBookingService;
        this.cancellationService = cancellationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createShipment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody B2bBookingRequest request) {
        // Only B2B accounts may book on credit (ADMIN allowed for ops/demo). The service
        // additionally checks the caller owns the specific b2b_account_id.
        Authz.requireRole(principal, "B2B_USER");
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
}
