package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Stand-in for the missing self-drop confirmation trigger (active outside prod only): {@code
 * BOOKED → AWAITING_SELF_DROP} is a registered state-machine edge that nothing in the product
 * actually calls yet (unlike DA_PICKUP, which M5's event-driven assignment drives automatically).
 * Lets a self-drop shipment reach a hub-receivable state for testing downstream modules (M7/M9)
 * without needing to build the real trigger, which would likely be a barcode/QR scan-in at the
 * hub dock, not a customer-invoked API. Scoped to the caller's own booking, same as cancel.
 */
@RestController
@RequestMapping("/api/v1/b2c/shipments")
@Profile("!prod")
class B2cSelfDropDemoController {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStateMachine stateMachine;

    B2cSelfDropDemoController(ShipmentRepository shipmentRepository, ShipmentStateMachine stateMachine) {
        this.shipmentRepository = shipmentRepository;
        this.stateMachine = stateMachine;
    }

    @PostMapping("/{ref}/confirm-self-drop")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public void confirmSelfDrop(@PathVariable("ref") String ref, @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER");
        String userId = Authz.requireUserId(principal);

        Shipment shipment = shipmentRepository.findByShipmentRef(ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown shipment: " + ref));
        if (shipment.getBookedByUserId() == null || !shipment.getBookedByUserId().equals(UUID.fromString(userId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown shipment: " + ref);
        }
        if (shipment.getPickupType() != PickupType.SELF_DROP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not a self-drop shipment: " + ref);
        }

        stateMachine.transition(shipment.getId(), ShipmentState.AWAITING_SELF_DROP,
                TransitionContext.fromApi(userId, ref).withNotes("Self-drop confirmation stand-in"));
    }
}
