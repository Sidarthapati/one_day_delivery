package com.oneday.orders.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for last-mile delivery OTP behaviour (drop-side mirror of {@link PickupOtpProperties}).
 *
 * <pre>{@code
 * orders:
 *   delivery-otp:
 *     ttl-minutes: 10
 *     max-resend-count: 3
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "orders.delivery-otp")
@Validated
public class DeliveryOtpProperties {

    /** How long a generated delivery OTP is valid. Default: 10 minutes. */
    @Positive
    private int ttlMinutes = 10;

    /** Maximum number of resend attempts before the endpoint returns 429. Default: 3. */
    @Positive
    private int maxResendCount = 3;

    public int getTtlMinutes() { return ttlMinutes; }
    public void setTtlMinutes(int ttlMinutes) { this.ttlMinutes = ttlMinutes; }

    public int getMaxResendCount() { return maxResendCount; }
    public void setMaxResendCount(int maxResendCount) { this.maxResendCount = maxResendCount; }
}
