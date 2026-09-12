package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.BarcodeResolution;
import com.oneday.orders.service.ShipmentLookupService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Barcode → active-shipment resolver (R1: reuse the same physical barcode for the return). A hub
 * operator or DA scans the label; this returns the shipment the scan should attach to — the live
 * return child {@code <ref>_R} once an RTO is under way, else the original — so the system
 * automatically recognises "this is now an RTO" without a new label. Hub/station and DA personas
 * only (ADMIN allowed).
 */
@RestController
@RequestMapping("/api/v1/shipments/by-barcode")
class BarcodeScanController {

    private static final String STATION_MANAGER = "STATION_MANAGER";
    private static final String DELIVERY_ASSOCIATE = "DELIVERY_ASSOCIATE";

    private final ShipmentLookupService lookupService;

    BarcodeScanController(ShipmentLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @GetMapping("/{parcelId}")
    public BarcodeResolution resolve(@AuthenticationPrincipal AuthUserDetails principal,
                                     @PathVariable("parcelId") String parcelId) {
        Authz.requireRole(principal, STATION_MANAGER, DELIVERY_ASSOCIATE);
        return lookupService.findActiveByParcelId(parcelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No shipment found for barcode " + parcelId));
    }
}
