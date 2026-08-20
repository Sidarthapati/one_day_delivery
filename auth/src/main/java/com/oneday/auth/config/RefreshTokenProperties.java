package com.oneday.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Refresh-token config, bound under {@code godspeed.refresh}. Issuing refresh tokens is on by
 * default (harmless + testable on staging); prod additionally cuts the access-token TTL to minutes
 * so a stolen access JWT is short-lived and the refresh token carries the long-lived, revocable
 * session. See {@code JwtServiceImpl} for the access TTL.
 *
 * <pre>{@code
 * godspeed:
 *   refresh:
 *     enabled: true
 *     ttl: 14d
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "godspeed.refresh")
public class RefreshTokenProperties {

    /** When false, login responses omit a refresh token (pure stateless-JWT mode). */
    private boolean enabled = true;

    /** How long a refresh token lives before it must be re-obtained via a fresh login. */
    private Duration ttl = Duration.ofDays(14);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
