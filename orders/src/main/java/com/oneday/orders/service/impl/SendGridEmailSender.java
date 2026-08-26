package com.oneday.orders.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.orders.config.NotifyProperties;
import com.oneday.orders.service.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * SendGrid email adapter. Gated by {@code notify.email.provider=sendgrid} — off by default. Sends
 * via the SendGrid v3 mail API; credentials from {@link NotifyProperties} (env only). Best-effort:
 * failures are logged and swallowed.
 *
 * <p>Untested against the live SendGrid API — this is the seam, not a validated integration.
 */
@Component
@ConditionalOnProperty(name = "notify.email.provider", havingValue = "sendgrid")
class SendGridEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailSender.class);
    private static final String SEND_URL = "https://api.sendgrid.com/v3/mail/send";

    private final NotifyProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    SendGridEmailSender(NotifyProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        log.info("[notify] email provider=sendgrid (from={})", props.getEmail().getFromEmail());
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        try {
            Map<String, Object> payload = Map.of(
                    "personalizations", List.of(Map.of("to", List.of(Map.of("email", toEmail)))),
                    "from", Map.of("email", props.getEmail().getFromEmail(), "name", props.getEmail().getFromName()),
                    "subject", subject,
                    "content", List.of(Map.of("type", "text/plain", "value", body)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(SEND_URL))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getEmail().getSendgridApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new com.oneday.orders.service.NotificationDeliveryException(
                        "sendgrid " + resp.statusCode() + " for " + toEmail + ": " + resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // only an actual interrupt sets the flag
            throw new com.oneday.orders.service.NotificationDeliveryException(
                    "interrupted sending to " + toEmail, e);
        } catch (com.oneday.orders.service.NotificationDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new com.oneday.orders.service.NotificationDeliveryException(
                    "sendgrid send to " + toEmail + " failed: " + e, e);
        }
    }
}
