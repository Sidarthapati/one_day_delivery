package com.oneday.orders.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Resilience4j circuit breakers and time limiters used by the booking flow.
 *
 * <pre>{@code
 * orders:
 *   resilience:
 *     failure-rate-threshold: 50.0
 *     minimum-number-of-calls: 10
 *     sliding-window-size: 20
 *     serviceability-timeout-ms: 500
 *     pricing-timeout-ms: 500
 *     payment-timeout-ms: 3000
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "orders.resilience")
@Validated
public class ResilienceProperties {

    /** Percentage of calls that must fail before the circuit opens. Default: 50%. */
    @Positive
    private float failureRateThreshold = 50.0f;

    /** Minimum number of calls before the failure rate is evaluated. Default: 10. */
    @Positive
    private int minimumNumberOfCalls = 10;

    /** Number of calls in the sliding window used to compute the failure rate. Default: 20. */
    @Positive
    private int slidingWindowSize = 20;

    /** Max wait for serviceability (M3) response in milliseconds. Default: 500ms. */
    @Positive
    private int serviceabilityTimeoutMs = 500;

    /** Max wait for pricing (M2) response in milliseconds. Default: 500ms. */
    @Positive
    private int pricingTimeoutMs = 500;

    /** Max wait for Razorpay verify+capture in milliseconds. Default: 3000ms. */
    @Positive
    private int paymentTimeoutMs = 3000;

    public float getFailureRateThreshold() { return failureRateThreshold; }
    public void setFailureRateThreshold(float v) { this.failureRateThreshold = v; }

    public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
    public void setMinimumNumberOfCalls(int v) { this.minimumNumberOfCalls = v; }

    public int getSlidingWindowSize() { return slidingWindowSize; }
    public void setSlidingWindowSize(int v) { this.slidingWindowSize = v; }

    public int getServiceabilityTimeoutMs() { return serviceabilityTimeoutMs; }
    public void setServiceabilityTimeoutMs(int v) { this.serviceabilityTimeoutMs = v; }

    public int getPricingTimeoutMs() { return pricingTimeoutMs; }
    public void setPricingTimeoutMs(int v) { this.pricingTimeoutMs = v; }

    public int getPaymentTimeoutMs() { return paymentTimeoutMs; }
    public void setPaymentTimeoutMs(int v) { this.paymentTimeoutMs = v; }
}
