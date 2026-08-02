package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.InvoiceResponse;
import com.oneday.orders.service.InvoiceService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tax invoices for the authenticated customer — available to every customer type (C2C/B2C/B2B),
 * not just business accounts. Generated lazily from the shipment's stored pricing on first fetch.
 */
@RestController
@RequestMapping("/api/v1/invoices")
class InvoiceController {

    private final InvoiceService invoiceService;

    InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping("/mine")
    public List<InvoiceResponse> mine(@AuthenticationPrincipal AuthUserDetails principal) {
        return invoiceService.listMine(UUID.fromString(Authz.requireUserId(principal)));
    }

    @GetMapping("/{shipmentRef}")
    public InvoiceResponse forShipment(@PathVariable("shipmentRef") String shipmentRef,
                                       @AuthenticationPrincipal AuthUserDetails principal) {
        return invoiceService.getForShipment(shipmentRef, UUID.fromString(Authz.requireUserId(principal)));
    }
}
