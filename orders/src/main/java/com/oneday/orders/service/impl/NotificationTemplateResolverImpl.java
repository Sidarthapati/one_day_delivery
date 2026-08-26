package com.oneday.orders.service.impl;

import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.orders.domain.NotificationChannel;
import com.oneday.orders.service.NotificationTemplateResolver;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @see NotificationTemplateResolver
 */
@Component
class NotificationTemplateResolverImpl implements NotificationTemplateResolver {

    /** channels this event delivers over; {@code subject} is used for email only. */
    private record Template(Set<NotificationChannel> channels, String subject, String body) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

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

        TEMPLATES.put(NotificationEventType.WALLET_LOW, new Template(
                EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.SMS),
                "Your Godspeed wallet is running low",
                "Your Godspeed wallet balance is now ₹{balance}. Top up to keep shipping without interruption."));
    }

    @Override
    public Optional<Rendered> resolve(NotificationEventType type, Map<String, String> params) {
        Template t = TEMPLATES.get(type);
        if (t == null) {
            return Optional.empty();
        }
        Map<String, String> safe = params == null ? Map.of() : params;
        return Optional.of(new Rendered(t.channels(), render(t.subject(), safe), render(t.body(), safe)));
    }

    /**
     * Substitute every {@code {key}} present in {@code params} in a SINGLE pass over the original
     * pattern; unknown placeholders are left as-is. Single-pass matters: a param value that itself
     * contains {@code {something}} must not be re-substituted into a later placeholder.
     */
    private static String render(String pattern, Map<String, String> params) {
        if (pattern == null || params.isEmpty()) {
            return pattern;
        }
        Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = params.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
