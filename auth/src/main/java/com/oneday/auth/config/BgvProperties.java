package com.oneday.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Background-verification (BGV) vendor config. Defaults to a deterministic <b>mock</b> — no external
 * calls, no cost — so DA onboarding works out of the box. Set {@code bgv.live=true} with real
 * {@code api-key}/{@code api-secret} (env: BGV_LIVE / BGV_API_KEY / BGV_API_SECRET) once a vendor is
 * chosen. <b>Never commit real keys</b> — they live only in the gitignored {@code .env}.
 *
 * <p>{@code enabled} gates whether submitting a candidate runs BGV at all (true = the funnel goes
 * SUBMITTED → BGV_IN_PROGRESS; false = straight to the approval queue).
 */
@Component
@ConfigurationProperties(prefix = "bgv")
public class BgvProperties {

    /** Run BGV on submit. Default true. */
    private boolean enabled = true;

    /** When true, call a real vendor. Default false → deterministic mock. No real adapter is wired yet. */
    private boolean live = false;

    /** Vendor base URL (set once a vendor is chosen). */
    private String baseUrl = "";

    /** Vendor API key. */
    private String apiKey = "key_test_mock_bgv";

    /** Vendor API secret. */
    private String apiSecret = "secret_test_mock_bgv";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isLive() { return live; }
    public void setLive(boolean live) { this.live = live; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
}
