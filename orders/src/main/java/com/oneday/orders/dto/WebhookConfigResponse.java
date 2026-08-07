package com.oneday.orders.dto;

/** A merchant's webhook configuration. Secret is shown so they can configure their receiver. */
public record WebhookConfigResponse(String url, String secret, boolean active) {

    public static WebhookConfigResponse none() {
        return new WebhookConfigResponse(null, null, false);
    }
}
