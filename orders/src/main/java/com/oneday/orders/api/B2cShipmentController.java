package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.service.BookingService;
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
@RequestMapping("/api/v1/b2c/shipments")
class B2cShipmentController {

    private final BookingService bookingService;
    private final CancellationService cancellationService;

    B2cShipmentController(BookingService bookingService, CancellationService cancellationService) {
        this.bookingService = bookingService;
        this.cancellationService = cancellationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createShipment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody BookingRequest request) {
        // Only customer accounts may book retail shipments (ADMIN allowed for ops/demo).
        Authz.requireRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER");
        return bookingService.book(request, idempotencyKey, Authz.requireUserId(principal));
    }

    @DeleteMapping("/{ref}")
    @ResponseStatus(HttpStatus.OK)
    public CancellationResponse cancelShipment(
            @PathVariable("ref") String ref,
            @RequestParam(value = "reason", required = false) String reason,
            @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER");
        return cancellationService.cancel(ref, reason, Authz.requireUserId(principal), false);
    }
}
