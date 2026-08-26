package com.oneday.orders.service;

/**
 * A booking asked for a pickup slot that is already at capacity for its origin city. Thrown before
 * payment so a full slot never charges the customer; surfaced as HTTP 409 (see the orders handler).
 */
public class PickupSlotFullException extends RuntimeException {
    public PickupSlotFullException(String message) {
        super(message);
    }
}
