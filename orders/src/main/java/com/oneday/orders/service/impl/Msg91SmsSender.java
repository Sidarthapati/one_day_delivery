package com.oneday.orders.service.impl;

import com.oneday.orders.config.NotifyProperties;
import com.oneday.orders.service.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * MSG91 SMS adapter (India). Gated by {@code notify.sms.provider=msg91} — off by default, like
 * {@code RazorpayXPayoutAdapter}. Sends via the MSG91 v5 flow API using a DLT-approved template.
 * Credentials come from {@link NotifyProperties} (env only, never committed). Best-effort: any
 * failure is logged and swallowed so a dead gateway never affects the caller.
 *
 * <p>Untested against the live MSG91 API (no account yet) — this is the seam, not a validated
 * integration. Verify the payload against MSG91 docs before go-live.
 */
@Component
@ConditionalOnProperty(name = "notify.sms.provider", havingValue = "msg91")
class Msg91SmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(Msg91SmsSender.class);
    private static final String FLOW_URL = "https://control.msg91.com/api/v5/flow/";

    private final NotifyProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    Msg91SmsSender(NotifyProperties props) {
        this.props = props;
        log.info("[notify] sms provider=msg91 (sender={}, template={})",
                props.getSms().getMsg91SenderId(), props.getSms().getMsg91TemplateId());
    }

    @Override
    public void send(String phone, String message) {
        try {
            String mobile = phone.startsWith("+") ? phone.substring(1) : phone;
            // MSG91 flow templates carry the copy; we pass the message as a var. Escape quotes.
            String body = "{\"template_id\":\"" + props.getSms().getMsg91TemplateId() + "\","
                    + "\"sender\":\"" + props.getSms().getMsg91SenderId() + "\","
                    + "\"recipients\":[{\"mobiles\":\"" + mobile + "\",\"message\":\""
                    + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}]}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(FLOW_URL))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .header("authkey", props.getSms().getMsg91AuthKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[notify:sms] msg91 returned {} for {}: {}", resp.statusCode(), mobile, resp.body());
            }
        } catch (Exception e) {
            log.warn("[notify:sms] msg91 send to {} failed: {}", phone, e.toString());
            Thread.currentThread().interrupt();
        }
    }
}
