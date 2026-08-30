package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.dto.OrderCancellationSummary;
import com.oneday.orders.service.OrderRepairService;
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

/**
 * B2B order repair (business portal only). Add a shipment to an existing order, or cancel the whole
 * order, while it has not yet been fully picked up. Removing a single shipment is the existing
 * per-shipment cancel ({@code DELETE /api/v1/b2b/shipments/{ref}}). All gated to B2B users; the
 * service additionally enforces that the caller owns the order's account.
 */
@RestController
@RequestMapping("/api/v1/b2b/orders")
class B2bOrderController {

    private final OrderRepairService orderRepairService;

    B2bOrderController(OrderRepairService orderRepairService) {
        this.orderRepairService = orderRepairService;
    }

    @PostMapping("/{orderRef}/shipments")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse addShipment(
            @PathVariable("orderRef") String orderRef,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody B2bBookingRequest request) {
        Authz.requireCustomerRole(principal, "B2B_USER");
        return orderRepairService.addShipment(orderRef, request, idempotencyKey,
                Authz.requireUserId(principal));
    }

    @DeleteMapping("/{orderRef}")
    @ResponseStatus(HttpStatus.OK)
    public OrderCancellationSummary cancelOrder(
            @PathVariable("orderRef") String orderRef,
            @RequestParam(value = "reason", required = false) String reason,
            @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        return orderRepairService.cancelOrder(orderRef, reason, Authz.requireUserId(principal));
    }
}
