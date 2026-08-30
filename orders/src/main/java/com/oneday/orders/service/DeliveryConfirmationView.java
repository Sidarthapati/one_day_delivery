package com.oneday.orders.service;

/**
 * What the no-login landing page renders: the parcel + a human ETD + whether the receiver can still
 * respond. Carries no address/contact detail — the opaque token is the only capability.
 *
 * @param status   PENDING | ACCEPTED | REJECTED | EXPIRED
 * @param etaDay   TODAY | NEXT_DAY
 * @param etaShift SHIFT_1 | SHIFT_2
 * @param etaText  human framing, e.g. "today by about 6 PM" / "tomorrow morning"
 * @param canRespond true while PENDING and unexpired
 */
public record DeliveryConfirmationView(
        String shipmentRef,
        String receiverName,
        String status,
        String etaDay,
        String etaShift,
        String etaText,
        String responseShift,
        boolean canRespond
) {}
