package com.oneday.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Phone-OTP tuning (auth.otp.*). Defaults match application.properties. */
@Component
@ConfigurationProperties(prefix = "auth.otp")
public class OtpProperties {

    /** Number of digits in the generated code. */
    private int length = 6;
    /** How long a code stays valid. */
    private long ttlSeconds = 300;
    /** Wrong-code attempts allowed before the code is dead and a new one is required. */
    private int maxAttempts = 5;

    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }

    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
