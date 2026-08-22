package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.MeasurementView;
import com.oneday.orders.dto.MyShipmentDetailResponse;
import com.oneday.orders.dto.MyShipmentSummaryResponse;
import com.oneday.orders.dto.ShipmentLabelResponse;
import com.oneday.orders.service.CustomerOrderQueryService;
import com.oneday.orders.service.ParcelMeasurementService;
import com.oneday.orders.service.PickupOtpService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final PickupOtpService pickupOtpService;
    private final ParcelMeasurementService parcelMeasurementService;

    MyShipmentsController(CustomerOrderQueryService customerOrderQueryService,
                         PickupOtpService pickupOtpService,
                         ParcelMeasurementService parcelMeasurementService) {
        this.customerOrderQueryService = customerOrderQueryService;
        this.pickupOtpService = pickupOtpService;
        this.parcelMeasurementService = parcelMeasurementService;
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

    @GetMapping("/mine/{ref}/label")
    public ShipmentLabelResponse shipmentLabel(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("ref") String ref) {
        Authz.requireCustomerRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER");
        return customerOrderQueryService
                .shipmentLabel(Authz.requireUserId(principal), ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shipment: " + ref));
    }

    /**
     * The dimension measurements recorded for the caller's own shipment (declared vs measured +
     * evidence photos), so the merchant can see the proof behind any weight/size adjustment. Evidence
     * photo URLs are short-lived presigned GETs. Owner-scoped: 404 if the caller didn't book it.
     */
    @GetMapping("/mine/{ref}/measurements")
    public List<MeasurementView> myShipmentMeasurements(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("ref") String ref) {
        Authz.requireCustomerRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER");
        // Ownership gate: reuse the detail lookup (empty → not the caller's shipment → 404).
        customerOrderQueryService.myShipmentDetail(Authz.requireUserId(principal), ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shipment: " + ref));
        return parcelMeasurementService.history(ref, true);
    }

    /** The pickup OTP for the caller's own shipment, so the merchant can read it to the pickup associate. */
    @GetMapping("/mine/{ref}/pickup-otp")
    public PickupOtpView pickupOtp(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("ref") String ref) {
        Authz.requireCustomerRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER");
        String otp = pickupOtpService.peekForOwner(Authz.requireUserId(principal), ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No pickup OTP available yet for " + ref));
        return new PickupOtpView(ref, otp);
    }

    /** Mint a fresh pickup OTP (e.g. the previous one expired). Only while awaiting pickup. */
    @PostMapping("/mine/{ref}/pickup-otp/regenerate")
    public PickupOtpView regeneratePickupOtp(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("ref") String ref) {
        Authz.requireCustomerRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER");
        String otp;
        try {
            otp = pickupOtpService.regenerateForOwner(Authz.requireUserId(principal), ref)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shipment: " + ref));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return new PickupOtpView(ref, otp);
    }

    record PickupOtpView(String shipmentRef, String otp) {}
}
