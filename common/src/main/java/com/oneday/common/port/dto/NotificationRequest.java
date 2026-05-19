package com.oneday.common.port.dto;

import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;

/**
 * Payload published to oneday.notifications.requested. The notification service
 * selects channels (SMS/email/WhatsApp) and templates based on type and newState.
 *
 * @param type           OTP_GENERATED or STATE_CHANGED
 * @param recipientPhone E.164 format, e.g. "+919876543210"
 * @param recipientEmail nullable; not all customers provide email
 * @param shipmentRef    human-readable ref, e.g. "1DD-BLR-20260519-000042"
 * @param newState       the state just entered; null for OTP_GENERATED
 * @param otp            4-digit code; null for STATE_CHANGED
 * @param eta            latest ETA; null if not applicable to this notification
 */
public record NotificationRequest(
        NotificationEventType type,
        String recipientPhone,
        String recipientEmail,
        String shipmentRef,
        ShipmentState newState,
        String otp,
        Instant eta
) {}
