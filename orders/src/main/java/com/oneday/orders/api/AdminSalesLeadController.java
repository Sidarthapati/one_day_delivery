package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.SalesLeadResponse;
import com.oneday.orders.dto.UpdateSalesLeadStatusRequest;
import com.oneday.orders.service.SalesLeadService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ADMIN sales-lead queue: list inbound leads and advance their status. */
@RestController
@RequestMapping("/api/v1/admin/sales")
class AdminSalesLeadController {

    private final SalesLeadService leads;

    AdminSalesLeadController(SalesLeadService leads) {
        this.leads = leads;
    }

    @GetMapping("/leads")
    public List<SalesLeadResponse> list(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "status", required = false) String status) {
        Authz.requireRole(principal, "ADMIN");
        return leads.list(status);
    }

    @PatchMapping("/leads/{id}")
    public SalesLeadResponse updateStatus(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateSalesLeadStatusRequest request) {
        Authz.requireRole(principal, "ADMIN");
        return leads.updateStatus(id, request.getStatus());
    }
}
