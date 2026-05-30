package com.oneday.orders.api;

import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/b2c/shipments")
class B2cShipmentController {

    private final BookingService bookingService;

    B2cShipmentController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createShipment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            // TODO(SECURITY/M1): Replace X-User-Id with @AuthenticationPrincipal once M1/auth is
            // integrated. This header is client-forgeable — no JWT validation is performed yet.
            // See M4-ORDERS-DESIGN.md §17.3; auth must be wired before any prod deployment.
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody BookingRequest request) {
        return bookingService.book(request, idempotencyKey, userId);
    }
}
