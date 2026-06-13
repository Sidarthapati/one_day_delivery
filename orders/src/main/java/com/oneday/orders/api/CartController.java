package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.enums.CustomerType;
import com.oneday.orders.config.RazorpayProperties;
import com.oneday.orders.dto.AddCartItemRequest;
import com.oneday.orders.dto.CartCheckoutRequest;
import com.oneday.orders.dto.CartCheckoutResponse;
import com.oneday.orders.dto.CartItemResponse;
import com.oneday.orders.dto.CartResponse;
import com.oneday.orders.dto.PaymentOrderResponse;
import com.oneday.orders.service.CartService;
import com.oneday.orders.service.PaymentPort;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Shipment cart for customer roles. Each line is an independent shipment; checkout books them
 * all (B2C → one Razorpay payment for the total, B2B → credit-account debit per item). ADMIN is
 * barred from booking (same rule as the shipment controllers); it reads the DB directly.
 */
@RestController
@RequestMapping("/api/v1/cart")
class CartController {

    private static final String[] CUSTOMER_ROLES = {"C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER"};

    private final CartService cartService;
    private final PaymentPort paymentPort;
    private final RazorpayProperties razorpayProps;

    CartController(CartService cartService, PaymentPort paymentPort, RazorpayProperties razorpayProps) {
        this.cartService = cartService;
        this.paymentPort = paymentPort;
        this.razorpayProps = razorpayProps;
    }

    @GetMapping
    public CartResponse getCart(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        return cartService.getCart(userId(principal));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartItemResponse addItem(@AuthenticationPrincipal AuthUserDetails principal,
                                    @Valid @RequestBody AddCartItemRequest request) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        return cartService.addItem(userId(principal), request);
    }

    @PutMapping("/items/{id}")
    public CartItemResponse updateItem(@AuthenticationPrincipal AuthUserDetails principal,
                                       @PathVariable("id") UUID id,
                                       @Valid @RequestBody AddCartItemRequest request) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        return cartService.updateItem(userId(principal), id, request);
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@AuthenticationPrincipal AuthUserDetails principal,
                           @PathVariable("id") UUID id) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        cartService.removeItem(userId(principal), id);
    }

    /**
     * Mints a single Razorpay order for the whole-cart total (B2C/C2C prepaid checkout). The client
     * pays this once, then posts the resulting refs to {@code /checkout}. B2B uses credit, not this.
     */
    @PostMapping("/payment-order")
    public PaymentOrderResponse paymentOrder(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        long total = cartService.getCart(userId(principal)).cartTotalPaise();
        if (total <= 0) {
            throw new CartService.EmptyCartException("Cart is empty — nothing to pay for");
        }
        PaymentPort.PaymentOrder order = paymentPort.createOrder(total, "cart-checkout");
        return new PaymentOrderResponse(order.orderId(), order.amountPaise(), order.currency(),
                order.keyId(), !razorpayProps.isLive());
    }

    @PostMapping("/checkout")
    public CartCheckoutResponse checkout(@AuthenticationPrincipal AuthUserDetails principal,
                                         @Valid @RequestBody CartCheckoutRequest request) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        return cartService.checkout(userId(principal), customerType(principal), request);
    }

    private static UUID userId(AuthUserDetails principal) {
        return UUID.fromString(Authz.requireUserId(principal));
    }

    /** Lane from the caller's role: B2B_USER → B2B (credit), C2C → C2C, otherwise B2C. */
    private static CustomerType customerType(AuthUserDetails principal) {
        String role = principal.getUser().getRole().getName();
        return switch (role) {
            case "B2B_USER" -> CustomerType.B2B;
            case "C2C_CUSTOMER" -> CustomerType.C2C;
            default -> CustomerType.B2C;
        };
    }
}
