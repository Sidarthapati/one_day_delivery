package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Razorpay credentials/config. Defaults run a self-contained <b>mock/test gateway</b>
 * (no real Razorpay account needed): orders are minted locally and signatures use the
 * same HMAC-SHA256 algorithm as production, so the verify path is real. Set
 * {@code razorpay.live=true} with real {@code key-id}/{@code key-secret} to go live.
 */
@Component
@ConfigurationProperties(prefix = "razorpay")
public class RazorpayProperties {

    /** When true, call the real Razorpay API. Default false → local mock/test gateway. */
    private boolean live = false;

    /** Razorpay key id, surfaced to the checkout UI. Test placeholder by default. */
    private String keyId = "rzp_test_1DDdemo";

    /** Secret used for HMAC-SHA256 order|payment signing + verification. */
    private String keySecret = "mock_secret_1dd_demo_v1_change_me";

    public boolean isLive() { return live; }
    public void setLive(boolean live) { this.live = live; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
}
