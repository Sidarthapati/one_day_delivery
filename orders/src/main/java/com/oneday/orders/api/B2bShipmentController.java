package com.oneday.orders.api;

import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.service.B2bBookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/b2b/shipments")
class B2bShipmentController {

    private final B2bBookingService b2bBookingService;

    B2bShipmentController(B2bBookingService b2bBookingService) {
        this.b2bBookingService = b2bBookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createShipment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            // TODO(SECURITY/M1): Replace X-User-Id with @AuthenticationPrincipal once M1/auth is
            // integrated. This header is client-forgeable — no JWT validation is performed yet.
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody B2bBookingRequest request) {
        return b2bBookingService.book(request, idempotencyKey, userId);
    }
}
