package com.oneday.common.port.dto;

public enum NotificationEventType {
    OTP_GENERATED,
    STATE_CHANGED,
    // M10: an SLA leg went RED / breached — the notification service resolves the on-duty
    // supervisor / station manager for the parcel's city and pushes an ops alert.
    SLA_ESCALATION
}
