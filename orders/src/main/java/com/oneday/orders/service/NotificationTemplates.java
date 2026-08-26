package com.oneday.orders.service;

import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.orders.domain.NotificationChannel;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The one place that decides, per event, which channels fire and what the message says. A template is
 * a channel set + a subject (email) + a body, both with {@code {placeholder}} variables filled from
 * the request's params. New event types add an entry here (and a value to
 * {@link NotificationEventType}) — that's the whole extension point for a new notification.
 */
public final class NotificationTemplates {

    /** channels this event delivers over; {@code subject} is used for email only. */
    public record Template(Set<NotificationChannel> channels, String subject, String body) {}

    private static final Map<NotificationEventType, Template> TEMPLATES =
            new EnumMap<>(NotificationEventType.class);

    static {
        TEMPLATES.put(NotificationEventType.OTP_GENERATED, new Template(
                EnumSet.of(NotificationChannel.SMS),
                null,
                "Your Godspeed pickup OTP is {otp}. It expires in {ttl_minutes} minutes."));

        TEMPLATES.put(NotificationEventType.STATE_CHANGED, new Template(
                EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.SMS),
                "Update on your shipment {shipment_ref}",
                "Your shipment {shipment_ref} is now {status}."));

        TEMPLATES.put(NotificationEventType.SLA_ESCALATION, new Template(
                EnumSet.of(NotificationChannel.EMAIL),
                "SLA alert: {shipment_ref} needs attention",
                "Shipment {shipment_ref} ({city}) has breached its SLA: {detail}. Please action it."));
    }

    private NotificationTemplates() {}

    /** The template for an event, or {@code null} if none is defined (the service skips + logs). */
    public static Template forType(NotificationEventType type) {
        return TEMPLATES.get(type);
    }

    /** Substitute every {@code {key}} present in {@code params}; unknown placeholders are left as-is. */
    public static String render(String pattern, Map<String, String> params) {
        if (pattern == null || params.isEmpty()) {
            return pattern;
        }
        String out = pattern;
        for (Map.Entry<String, String> e : params.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }
}
