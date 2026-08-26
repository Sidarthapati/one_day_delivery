package com.oneday.common.port.dto;

public enum NotificationEventType {
    OTP_GENERATED,
    STATE_CHANGED,
    // M10: an SLA leg went RED / breached — the notification service resolves the on-duty
    // supervisor / station manager for the parcel's city and pushes an ops alert.
    SLA_ESCALATION,
    // M4: a B2B merchant's prepaid wallet just dropped below the low-balance threshold — alert the
    // account owner to top up before bookings start failing.
    WALLET_LOW,
    // M4: a shipment's delivery ETA slipped later than what was promised at booking — tell the
    // customer the new ETA and that they can wait, or cancel for a refund.
    SHIPMENT_DELAYED
}
