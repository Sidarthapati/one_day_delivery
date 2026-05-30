package com.oneday.orders.api;

import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.OtpVerifyRequest;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import com.oneday.common.domain.enums.ShipmentState;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Internal endpoints for DA pickup OTP verification.
 *
 * <p>These endpoints are on {@code /internal/v1/} and are called by the DA mobile
 * app (not exposed to end customers). Full {@code X-Service-Token} authentication
 * is added in PR #18 when the internal-endpoint auth infrastructure is built.
 * Until then these paths must be behind the existing JWT security filter —
 * only authenticated principals can reach them.</p>
 */
@RestController
@RequestMapping("/internal/v1/shipments")
public class PickupOtpController {

    private final ShipmentRepository shipmentRepository;
    private final PickupOtpService pickupOtpService;
    private final ShipmentStateMachine stateMachine;

    public PickupOtpController(ShipmentRepository shipmentRepository,
                               PickupOtpService pickupOtpService,
                               ShipmentStateMachine stateMachine) {
        this.shipmentRepository = shipmentRepository;
        this.pickupOtpService   = pickupOtpService;
        this.stateMachine       = stateMachine;
    }

    /**
     * DA submits the OTP shown by the sender. On success the shipment transitions
     * {@code PICKUP_ASSIGNED → PICKED_UP}.
     *
     * <pre>
     * POST /internal/v1/shipments/{ref}/pickup-otp/verify
     * Body: { "otp": "4821" }
     *
     * 204 No Content — OTP correct; state transitioned
     * 409 Conflict   — shipment is not in PICKUP_ASSIGNED state
     * 422 Unprocessable Entity — OTP wrong / expired / already used
     * 404 Not Found  — shipment ref does not exist
     * </pre>
     */
    @Transactional
    @PostMapping("/{ref}/pickup-otp/verify")
    public ResponseEntity<Void> verifyOtp(@PathVariable String ref,
                                          @Valid @RequestBody OtpVerifyRequest request) {
        Shipment shipment = resolveShipment(ref);

        if (shipment.getState() != ShipmentState.PICKUP_ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Shipment is not in PICKUP_ASSIGNED state");
        }

        try {
            pickupOtpService.verify(shipment.getId(), request.getOtp());
        } catch (PickupOtpService.OtpVerificationException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }

        // OTP verified — transition state.
        // triggeredBy = "da-app" (internal caller); eventRef = ref for correlation.
        // TODO (PR #18): replace "da-app" with the authenticated DA user ID from the service token.
        TransitionContext ctx = TransitionContext.fromApi("da-app", ref);
        stateMachine.transition(shipment.getId(), ShipmentState.PICKED_UP, ctx);

        return ResponseEntity.noContent().build();
    }

    /**
     * DA requests a fresh OTP (e.g. sender did not receive the SMS). Invalidates
     * the previous OTP and generates a new one. The new cleartext OTP is returned
     * to the caller who must forward it via the notification service.
     *
     * <p><strong>Note:</strong> In the full flow the notification is triggered here
     * server-side (calling {@code NotificationPort}). That wiring is added in PR #10
     * alongside the booking flow. For now the OTP is returned in the response body
     * so the DA app can display it as a fallback.</p>
     *
     * <pre>
     * POST /internal/v1/shipments/{ref}/pickup-otp/resend
     * Body: (empty)
     *
     * 200 OK         — { "otp": "3902" }   new OTP (sent to sender; also returned for DA display)
     * 404 Not Found  — shipment ref does not exist
     * 429 Too Many Requests — resend limit (3) reached
     * </pre>
     */
    @PostMapping("/{ref}/pickup-otp/resend")
    public ResponseEntity<Map<String, String>> resendOtp(@PathVariable String ref) {
        Shipment shipment = resolveShipment(ref);

        if (shipment.getState() != ShipmentState.PICKUP_ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Shipment is not in PICKUP_ASSIGNED state");
        }

        try {
            String newOtp = pickupOtpService.resend(shipment.getId());
            // TODO (PR #10): call NotificationPort to SMS the new OTP to the sender.
            return ResponseEntity.ok(Map.of("otp", newOtp));
        } catch (PickupOtpService.ResendLimitExceededException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        } catch (PickupOtpService.OtpVerificationException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Shipment resolveShipment(String ref) {
        return shipmentRepository.findByShipmentRef(ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Shipment not found: " + ref));
    }
}
