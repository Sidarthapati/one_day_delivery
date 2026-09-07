package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Inbound bank-credit webhook config (Discussion-3 ix). When {@code cod.webhook.secret} is set, the
 * provider (RazorpayX/bank) can confirm a COD deposit's credit landed by POSTing to
 * {@code /webhooks/cod/bank-credit}, HMAC-signed with this secret. Empty by default → the webhook is
 * disabled and finance uses the manual confirm endpoint instead. Go-live (real secret + a public URL +
 * the exact provider event shape) is tracked in the go-live issue, never committed.
 */
@Component
@ConfigurationProperties(prefix = "cod.webhook")
public class CodWebhookProperties {

    /** HMAC-SHA256 signing secret shared with the provider. Empty ⇒ webhook disabled. Env only. */
    private String secret = "";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public boolean isEnabled() {
        return secret != null && !secret.isBlank();
    }
}
