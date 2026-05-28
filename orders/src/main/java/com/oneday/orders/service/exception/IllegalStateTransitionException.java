package com.oneday.orders.service.exception;

import com.oneday.common.domain.enums.ShipmentState;

/**
 * Thrown by {@link com.oneday.orders.service.ShipmentStateMachine} when a requested
 * state transition is not permitted by the transition registry or runtime branching rules.
 *
 * <p>Maps to {@code 409 Conflict} at the API layer.</p>
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final ShipmentState fromState;
    private final ShipmentState toState;

    public IllegalStateTransitionException(ShipmentState fromState, ShipmentState toState) {
        super(String.format("Transition from %s to %s is not permitted", fromState, toState));
        this.fromState = fromState;
        this.toState = toState;
    }

    public ShipmentState getFromState() {
        return fromState;
    }

    public ShipmentState getToState() {
        return toState;
    }
}
