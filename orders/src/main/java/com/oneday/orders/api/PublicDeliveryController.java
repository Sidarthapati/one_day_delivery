package com.oneday.orders.api;

import com.oneday.orders.service.DeliveryConfirmationService;
import com.oneday.orders.service.DeliveryConfirmationView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No-login receiver accept/reject surface for the emailed delivery-confirmation link. Path is under
 * {@code /public/**} (permitted in SecurityConfig); the opaque token in the URL is the only capability,
 * so a scanner pre-fetching the GET can't act, and there is no address/contact leakage in the response.
 */
@RestController
@RequestMapping("/public/v1/deliveries")
class PublicDeliveryController {

    private final DeliveryConfirmationService service;

    PublicDeliveryController(DeliveryConfirmationService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    DeliveryConfirmationView get(@PathVariable String token) {
        return service.getByToken(token);
    }

    @PostMapping("/{token}/accept")
    DeliveryConfirmationView accept(@PathVariable String token) {
        return service.accept(token);
    }

    @PostMapping("/{token}/reject")
    DeliveryConfirmationView reject(@PathVariable String token, @RequestBody(required = false) RejectRequest body) {
        return service.reject(token, body != null ? body.targetShift() : null);
    }

    /** The next-day shift the receiver picked. */
    record RejectRequest(String targetShift) {}
}
