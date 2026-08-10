package com.oneday.orders.api;

import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.DevOtpRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dev-only pickup-OTP peek for field testing (active outside prod only). SMS is a log sink and the
 * OTP is stored BCrypt-hashed, so a tester acting as the sender has no way to read the code the DA
 * must enter. This echoes the cleartext OTP {@link DevOtpRegistry} captured at generation. Never in prod.
 */
@RestController
@RequestMapping("/internal/dev")
@Profile("!prod")
class DevOtpController {

    private final ShipmentRepository shipments;
    private final DevOtpRegistry registry;

    DevOtpController(ShipmentRepository shipments, DevOtpRegistry registry) {
        this.shipments = shipments;
        this.registry = registry;
    }

    @GetMapping("/shipments/{ref}/pickup-otp")
    public PickupOtpPeek peek(@PathVariable String ref) {
        Shipment shipment = shipments.findByShipmentRef(ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found: " + ref));
        String otp = registry.get(shipment.getId());
        if (otp == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No pickup OTP yet for " + ref + " — assign the pickup first");
        }
        return new PickupOtpPeek(ref, otp);
    }

    @GetMapping("/shipments/{ref}/delivery-otp")
    public DeliveryOtpPeek peekDelivery(@PathVariable String ref) {
        Shipment shipment = shipments.findByShipmentRef(ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found: " + ref));
        String otp = registry.getDelivery(shipment.getId());
        if (otp == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No delivery OTP yet for " + ref + " — the parcel must be out for delivery first");
        }
        return new DeliveryOtpPeek(ref, otp);
    }

    record PickupOtpPeek(String shipmentRef, String otp) {}

    record DeliveryOtpPeek(String shipmentRef, String otp) {}
}
