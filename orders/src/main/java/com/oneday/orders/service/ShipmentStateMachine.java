package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.service.exception.IllegalStateTransitionException;

import java.util.UUID;

/**
 * The canonical state machine for M4 shipment lifecycle.
 *
 * <p>Callers supply the target state and a {@link TransitionContext} describing
 * who triggered the transition and why. The implementation:</p>
 * <ol>
 *   <li>Acquires a pessimistic write lock (SELECT FOR UPDATE) on the shipment row</li>
 *   <li>Looks up allowed targets from {@link TransitionRegistry}</li>
 *   <li>Applies runtime branching rules (pickup_type, drop_type, delivery_type)</li>
 *   <li>Rejects invalid transitions with {@link IllegalStateTransitionException}</li>
 *   <li>Updates the shipment state and appends a {@code ShipmentStateHistory} row</li>
 * </ol>
 *
 * <p>Side effects (OTP generation, ETA calls, Kafka emission, notifications) are
 * NOT performed here — they live in the calling service layer ({@code ShipmentServiceImpl})
 * which wraps this machine.</p>
 *
 * <p>All callers must be inside a {@code @Transactional} method; the implementation
 * enforces this via its own {@code @Transactional} annotation.</p>
 */
public interface ShipmentStateMachine {

    /**
     * Transitions {@code shipmentId} to {@code target}.
     *
     * @param shipmentId the shipment to transition
     * @param target     the desired next state
     * @param ctx        metadata about who triggered this transition and why
     * @throws IllegalStateTransitionException if the transition is not permitted
     * @throws jakarta.persistence.EntityNotFoundException if the shipment does not exist
     */
    void transition(UUID shipmentId, ShipmentState target, TransitionContext ctx);
}
