package com.oneday.orders.service;

import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.orders.domain.NotificationChannel;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves and renders the message for a notification event. The one place that decides, per event,
 * which channels fire and what the copy says. A new notification = a new template entry in the impl
 * (plus a {@link NotificationEventType} value).
 */
public interface NotificationTemplateResolver {

    /** A rendered message: the channels to deliver on, plus the (email) subject and the body. */
    record Rendered(Set<NotificationChannel> channels, String subject, String body) {}

    /**
     * Resolve the template for {@code type} and render it with {@code params}, or empty when no
     * template is defined for the event (the caller drops it).
     */
    Optional<Rendered> resolve(NotificationEventType type, Map<String, String> params);
}
