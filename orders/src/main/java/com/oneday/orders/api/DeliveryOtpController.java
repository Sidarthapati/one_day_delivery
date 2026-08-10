package com.oneday.orders.api;

import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.OtpVerifyRequest;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.DeliveryOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.enums.ShipmentState;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Internal endpoints for last-mile <em>delivery</em> OTP verification — drop-side mirror of
 * {@link PickupOtpController}. Called by the DA mobile app at the recipient's door. The delivery OTP
 * gates the final hop to {@code DROPPED} from whichever "out for delivery" state the parcel is in:
 * {@code DROP_COLLECTED} (VAN_MEETING city — received off the drop van) or {@code COLLECTED_FROM_HUB}
 * (HUB_RETURN city — collected from the destination hub). Both are valid predecessors of {@code DROPPED}.
 */
@RestController
@RequestMapping("/internal/v1/shipments")
public class DeliveryOtpController {

    private final ShipmentRepository shipmentRepository;
    private final DeliveryOtpService deliveryOtpService;
    private final ShipmentStateMachine stateMachine;

    public DeliveryOtpController(ShipmentRepository shipmentRepository,
                                 DeliveryOtpService deliveryOtpService,
                                 ShipmentStateMachine stateMachine) {
        this.shipmentRepository = shipmentRepository;
        this.deliveryOtpService = deliveryOtpService;
        this.stateMachine       = stateMachine;
    }

    /**
     * DA submits the OTP read out by the recipient. On success the shipment transitions to
     * {@code DROPPED} from its "out for delivery" state ({@code DROP_COLLECTED} or {@code COLLECTED_FROM_HUB}).
     *
     * <pre>
     * POST /internal/v1/shipments/{ref}/delivery-otp/verify
     * Body: { "otp": "4821" }
     *
     * 204 No Content          — OTP correct; state transitioned to DROPPED
     * 409 Conflict            — shipment is not out for delivery (DROP_COLLECTED / COLLECTED_FROM_HUB)
     * 422 Unprocessable Entity — OTP wrong / expired / already used
     * 404 Not Found           — shipment ref does not exist
     * </pre>
     */
    @Transactional
    @PostMapping("/{ref}/delivery-otp/verify")
    public ResponseEntity<Void> verifyOtp(@PathVariable String ref,
                                          @AuthenticationPrincipal AuthUserDetails principal,
                                          @Valid @RequestBody OtpVerifyRequest request) {
        Authz.requireRole(principal, "DELIVERY_ASSOCIATE");
        String daUserId = Authz.requireUserId(principal);
        Shipment shipment = resolveShipment(ref);

        requireOutForDelivery(shipment);

        try {
            deliveryOtpService.verify(shipment.getId(), request.getOtp());
        } catch (DeliveryOtpService.OtpVerificationException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }

        TransitionContext ctx = TransitionContext.fromApi(daUserId, ref);
        stateMachine.transition(shipment.getId(), ShipmentState.DROPPED, ctx);

        return ResponseEntity.noContent().build();
    }

    /**
     * DA requests a fresh delivery OTP (e.g. the recipient did not receive it). Invalidates the
     * previous OTP and returns a new cleartext code.
     *
     * <pre>
     * POST /internal/v1/shipments/{ref}/delivery-otp/resend
     * 200 OK  — { "otp": "3902" }
     * 409 Conflict          — shipment is not out for delivery (DROP_COLLECTED / COLLECTED_FROM_HUB)
     * 429 Too Many Requests — resend limit reached
     * 404 Not Found         — shipment ref does not exist
     * </pre>
     */
    @PostMapping("/{ref}/delivery-otp/resend")
    public ResponseEntity<Map<String, String>> resendOtp(
            @PathVariable String ref,
            @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, "DELIVERY_ASSOCIATE");
        Shipment shipment = resolveShipment(ref);

        requireOutForDelivery(shipment);

        try {
            String newOtp = deliveryOtpService.resend(shipment.getId());
            return ResponseEntity.ok(Map.of("otp", newOtp));
        } catch (DeliveryOtpService.ResendLimitExceededException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        } catch (DeliveryOtpService.OtpVerificationException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    private Shipment resolveShipment(String ref) {
        return shipmentRepository.findByShipmentRef(ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Shipment not found: " + ref));
    }

    /**
     * The delivery OTP is only meaningful once the parcel is "out for delivery": received off the drop
     * van ({@code DROP_COLLECTED}) in a VAN_MEETING city, or collected from the destination hub
     * ({@code COLLECTED_FROM_HUB}) in a HUB_RETURN city. Both transition to {@code DROPPED} on verify.
     */
    private void requireOutForDelivery(Shipment shipment) {
        ShipmentState state = shipment.getState();
        if (state != ShipmentState.DROP_COLLECTED && state != ShipmentState.COLLECTED_FROM_HUB) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Shipment is not out for delivery (DROP_COLLECTED / COLLECTED_FROM_HUB)");
        }
    }
}
