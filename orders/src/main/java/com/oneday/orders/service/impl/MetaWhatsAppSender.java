package com.oneday.orders.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.orders.config.NotifyProperties;
import com.oneday.orders.service.NotificationDeliveryException;
import com.oneday.orders.service.WhatsAppSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Meta WhatsApp Cloud API adapter. Gated by {@code notify.whatsapp.provider=meta} — off by default,
 * like {@link Msg91SmsSender}. Sends a plain-text message via the Graph API using the account's phone
 * number id + access token. Credentials come from {@link NotifyProperties} (env only, never committed).
 * Best-effort: any failure is turned into a {@link NotificationDeliveryException} the outbox retries.
 *
 * <p><b>STUB — untested against the live Meta API (no BSP/WABA account yet).</b> This is the seam, not
 * a validated integration. Production WhatsApp requires pre-approved <i>message templates</i> for
 * business-initiated messages outside the 24-hour customer-service window; this sends a plain text body
 * (fine inside the window / for testing). Swap the payload to a {@code template} object, and verify the
 * phone-number-id / token wiring, before go-live.
 */
@Component
@ConditionalOnProperty(name = "notify.whatsapp.provider", havingValue = "meta")
class MetaWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppSender.class);
    private static final String GRAPH_BASE = "https://graph.facebook.com/v21.0/";

    private final NotifyProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    MetaWhatsAppSender(NotifyProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        log.info("[notify] whatsapp provider=meta (phoneNumberId={})", props.getWhatsapp().getPhoneNumberId());
    }

    @Override
    public void send(String phone, String message) {
        try {
            String to = phone.startsWith("+") ? phone.substring(1) : phone;   // Meta wants no leading +
            // Serialize with Jackson so both `to` and the message body are correctly escaped (no hand-rolled
            // JSON, no injection). ponytail: plain-text body — swap to a "template" object at go-live.
            String body = mapper.writeValueAsString(Map.of(
                    "messaging_product", "whatsapp",
                    "to", to,
                    "type", "text",
                    "text", Map.of("body", message)));
            URI url = URI.create(GRAPH_BASE + props.getWhatsapp().getPhoneNumberId() + "/messages");
            HttpRequest req = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getWhatsapp().getAccessToken())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new NotificationDeliveryException(
                        "meta whatsapp " + resp.statusCode() + " for " + to + ": " + resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationDeliveryException("interrupted sending whatsapp to " + phone, e);
        } catch (NotificationDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new NotificationDeliveryException("meta whatsapp send to " + phone + " failed: " + e, e);
        }
    }
}
