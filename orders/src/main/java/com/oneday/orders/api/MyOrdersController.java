package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.MyOrderDetailResponse;
import com.oneday.orders.dto.OrderSummaryResponse;
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
 * A customer's own orders (Order → N Shipments). The order-level counterpart to
 * {@link MyShipmentsController}: a merchant sees one card per booking and expands it to the parcels.
 * Lane-agnostic — serves every customer role and returns only the orders the caller placed.
 */
@RestController
@RequestMapping("/api/v1/orders")
class MyOrdersController {

    private final CustomerOrderQueryService customerOrderQueryService;

    MyOrdersController(CustomerOrderQueryService customerOrderQueryService) {
        this.customerOrderQueryService = customerOrderQueryService;
    }

    @GetMapping("/mine")
    public List<OrderSummaryResponse> myOrders(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        Authz.requireCustomerRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER");
        return customerOrderQueryService.myOrders(Authz.requireUserId(principal), limit);
    }

    @GetMapping("/mine/{orderRef}")
    public MyOrderDetailResponse myOrderDetail(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("orderRef") String orderRef) {
        Authz.requireCustomerRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER");
        return customerOrderQueryService
                .myOrderDetail(Authz.requireUserId(principal), orderRef)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such order: " + orderRef));
    }
}
