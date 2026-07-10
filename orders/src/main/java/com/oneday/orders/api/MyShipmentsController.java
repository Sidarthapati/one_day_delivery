package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.MyShipmentDetailResponse;
import com.oneday.orders.dto.MyShipmentSummaryResponse;
import com.oneday.orders.service.CustomerOrderQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * A customer's own booking history. Lane-agnostic: it serves every customer role (B2C/C2C/B2B)
 * and returns only the shipments the caller booked (matched on {@code booked_by_user_id}), so a
 * refresh re-loads the full history rather than just the current session's bookings.
 */
@RestController
@RequestMapping("/api/v1/shipments")
class MyShipmentsController {

    private final CustomerOrderQueryService customerOrderQueryService;

    MyShipmentsController(CustomerOrderQueryService customerOrderQueryService) {
        this.customerOrderQueryService = customerOrderQueryService;
    }

    @GetMapping("/mine")
    public List<MyShipmentSummaryResponse> myShipments(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        // Booking roles only — ADMIN/STATION_MANAGER use the admin orders database instead and
        // have no bookings of their own (no ADMIN bypass: requireCustomerRole, not requireRole).
        Authz.requireCustomerRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER");
        return customerOrderQueryService.myShipments(Authz.requireUserId(principal), limit);
    }

    @GetMapping("/mine/{ref}")
    public MyShipmentDetailResponse myShipmentDetail(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("ref") String ref) {
        Authz.requireCustomerRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER");
        return customerOrderQueryService
                .myShipmentDetail(Authz.requireUserId(principal), ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shipment: " + ref));
    }
}
