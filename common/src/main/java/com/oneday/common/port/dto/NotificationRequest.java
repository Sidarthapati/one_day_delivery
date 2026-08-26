package com.oneday.common.port.dto;

import java.util.Map;

/**
 * A request to notify a recipient of an event. The notification service resolves the template for
 * {@code type}, renders it with {@code params}, and delivers over whichever channels the template
 * declares AND the recipient supports (email when {@code recipientEmail} is set, SMS when
 * {@code recipientPhone} is set). Fire-and-forget for the caller — the service persists the request,
 * delivers it, and retries transient failures.
 *
 * @param type           what happened (selects the template)
 * @param recipientEmail nullable; enables the email channel
 * @param recipientPhone nullable; E.164 (e.g. "+919876543210"); enables the SMS channel
 * @param params         template variables, e.g. {@code {"otp":"123456","shipment_ref":"1DD-…"}}
 */
public record NotificationRequest(
        NotificationEventType type,
        String recipientEmail,
        String recipientPhone,
        Map<String, String> params
) {
    public NotificationRequest {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Convenience for a single-recipient email/SMS with no template variables. */
    public static NotificationRequest of(NotificationEventType type, String email, String phone) {
        return new NotificationRequest(type, email, phone, Map.of());
    }
}
