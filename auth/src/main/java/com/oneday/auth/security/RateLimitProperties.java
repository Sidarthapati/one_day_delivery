package com.oneday.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Rate-limiting config, bound under {@code godspeed.ratelimit}. Disabled by default so staging /
 * load testing runs unthrottled; {@code application-prod.yml} turns it on. Each rule is a token
 * bucket: {@code capacity} requests per {@code window}, per client key (IP or API-key hash).
 *
 * <pre>{@code
 * godspeed:
 *   ratelimit:
 *     enabled: true
 *     login:    { capacity: 10, window: 1m }
 *     otp:      { capacity: 5,  window: 1m }
 *     register: { capacity: 5,  window: 1m }
 *     api-key:  { capacity: 120, window: 1m }
 * }</pre>
 *
 * <p>In-memory + per-instance (fine for a single Render web service). A Redis-backed shared limiter
 * for multi-instance is deferred to Branch 2.
 */
@Component
@ConfigurationProperties(prefix = "godspeed.ratelimit")
public class RateLimitProperties {

    private boolean enabled = false;
    private Rule login = new Rule(10, Duration.ofMinutes(1));
    private Rule otp = new Rule(5, Duration.ofMinutes(1));
    private Rule register = new Rule(5, Duration.ofMinutes(1));
    private Rule apiKey = new Rule(120, Duration.ofMinutes(1));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Rule getLogin() {
        return login;
    }

    public void setLogin(Rule login) {
        this.login = login;
    }

    public Rule getOtp() {
        return otp;
    }

    public void setOtp(Rule otp) {
        this.otp = otp;
    }

    public Rule getRegister() {
        return register;
    }

    public void setRegister(Rule register) {
        this.register = register;
    }

    public Rule getApiKey() {
        return apiKey;
    }

    public void setApiKey(Rule apiKey) {
        this.apiKey = apiKey;
    }

    /** One token-bucket rule: {@code capacity} tokens refilled over {@code window}. */
    public static class Rule {
        private int capacity;
        private Duration window;

        public Rule() {
        }

        public Rule(int capacity, Duration window) {
            this.capacity = capacity;
            this.window = window;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }
}
