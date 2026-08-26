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

    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    /**
     * Substitute every {@code {key}} present in {@code params} in a SINGLE pass over the original
     * pattern; unknown placeholders are left as-is. Single-pass matters: a param value that itself
     * contains {@code {something}} must not be re-substituted (that would let template data inject
     * into later placeholders).
     */
    public static String render(String pattern, Map<String, String> params) {
        if (pattern == null || params.isEmpty()) {
            return pattern;
        }
        java.util.regex.Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = params.get(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
