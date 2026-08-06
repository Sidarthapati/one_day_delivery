package com.oneday.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * KYC/KYB provider (Sandbox.co.in) config. Defaults to a deterministic <b>mock</b> — no external
 * calls, no cost — so onboarding works out of the box. Set {@code kyc.live=true} with real
 * {@code api-key}/{@code api-secret} (env: KYC_LIVE / KYC_API_KEY / KYC_API_SECRET) to call the
 * provider. <b>Never commit real keys</b> — they live only in the gitignored {@code .env}.
 */
@Component
@ConfigurationProperties(prefix = "kyc")
public class KycProperties {

    /** When true, call the real provider. Default false → deterministic mock. */
    private boolean live = false;

    /** Provider base URL (Sandbox.co.in). */
    private String baseUrl = "https://api.sandbox.co.in";

    /** Provider API key (x-api-key). Mock placeholder by default. */
    private String apiKey = "key_test_mock_1dd";

    /** Provider API secret (x-api-secret). Mock placeholder by default. */
    private String apiSecret = "secret_test_mock_1dd";

    /** Provider API version header. */
    private String apiVersion = "2.0";

    public boolean isLive() { return live; }
    public void setLive(boolean live) { this.live = live; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
}
