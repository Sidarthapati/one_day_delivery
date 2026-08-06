package com.oneday.orders.api;

import com.oneday.orders.dto.CreateSalesLeadRequest;
import com.oneday.orders.dto.SalesLeadResponse;
import com.oneday.orders.service.SalesLeadService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public "Talk to sales" capture. Deliberately at {@code /api/sales} (not {@code /api/v1}) so it is
 * exempt from the idempotency filter (which requires an authenticated user) and can be permitted
 * for anonymous access in SecurityConfig.
 */
@RestController
@RequestMapping("/api/sales")
class SalesLeadController {

    private final SalesLeadService leads;

    SalesLeadController(SalesLeadService leads) {
        this.leads = leads;
    }

    @PostMapping("/leads")
    @ResponseStatus(HttpStatus.CREATED)
    public SalesLeadResponse submit(@Valid @RequestBody CreateSalesLeadRequest request) {
        return leads.create(request);
    }
}
