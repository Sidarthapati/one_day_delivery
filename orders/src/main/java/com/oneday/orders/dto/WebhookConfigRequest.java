package com.oneday.orders.dto;

/** Set the webhook URL and optionally rotate the signing secret. Blank url disables webhooks. */
public class WebhookConfigRequest {

    private String url;
    private boolean regenerateSecret;

    public String getUrl()                    { return url; }
    public void setUrl(String v)              { this.url = v; }

    public boolean isRegenerateSecret()       { return regenerateSecret; }
    public void setRegenerateSecret(boolean v){ this.regenerateSecret = v; }
}
