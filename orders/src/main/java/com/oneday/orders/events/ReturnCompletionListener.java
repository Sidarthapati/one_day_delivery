package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Closes the loop on a return: when a return child ({@code <ref>_R}) is delivered to the sender, drive
 * its original shipment RTO_INITIATED → RTO_COMPLETED (terminal, "returned to sender"). Listens to the
 * in-process {@link ShipmentTransitioned} AFTER_COMMIT so the child's delivery is durable first.
 */
@Component
public class ReturnCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(ReturnCompletionListener.class);
    private static final String SOURCE = "return-completion";

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStateMachine stateMachine;

    ReturnCompletionListener(ShipmentRepository shipmentRepository, ShipmentStateMachine stateMachine) {
        this.shipmentRepository = shipmentRepository;
        this.stateMachine = stateMachine;
    }

    // AFTER_COMMIT runs with no active transaction, so open a fresh one (REQUIRES_NEW) — the state
    // machine's pessimistic-lock read of the original needs a transaction in progress.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onShipmentTransitioned(ShipmentTransitioned e) {
        if (e.toState() != ShipmentState.DROPPED && e.toState() != ShipmentState.HUB_COLLECTED) {
            return; // only a delivered terminal matters here
        }
        Shipment child = shipmentRepository.findById(e.shipmentId()).orElse(null);
        if (child == null || child.getReturnOfShipmentId() == null) {
            return; // not a return child — nothing to close
        }
        try {
            stateMachine.transition(child.getReturnOfShipmentId(), ShipmentState.RTO_COMPLETED,
                    TransitionContext.fromSystem(SOURCE)
                            .withNotes("Return child " + child.getShipmentRef() + " delivered"));
            log.info("Return child {} delivered → original {} RTO_COMPLETED",
                    child.getShipmentRef(), child.getReturnOfShipmentId());
        } catch (IllegalStateTransitionException ex) {
            // Original already terminal (e.g. a duplicate delivery signal) — nothing to do.
            log.debug("Original {} not in a state to complete RTO: {}", child.getReturnOfShipmentId(), ex.getMessage());
        }
    }
}
