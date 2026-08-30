package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * Receiver delivery-confirmation tuning (see {@code DeliveryConfirmationService}).
 *
 * <pre>{@code
 * orders:
 *   delivery:
 *     last-mile-window-minutes: 120   # dest hub → door
 *     hub-processing-minutes: 60      # landing → sorted for delivery
 *     same-day-cutoff: "18:00"        # latest a same-day delivery can still land (Shift-2 end)
 *     confirmation-ttl-minutes: 360   # how long the accept/reject link stays live
 *     customer-landing-base-url: ${CUSTOMER_LANDING_BASE_URL}  # per-env, never baked in code
 *     zone: Asia/Kolkata
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "orders.delivery")
public class OrdersDeliveryProperties {

    /** Dest-hub → recipient's door window used to project the ETD. */
    private int lastMileWindowMinutes = 120;
    /** Flight landing → sorted-for-delivery processing at the destination hub. */
    private int hubProcessingMinutes = 60;
    /** Latest wall-clock a parcel can still be delivered same-day (≈ Shift-2 end). */
    private LocalTime sameDayCutoff = LocalTime.of(18, 0);
    /** How long the receiver's no-login accept/reject link stays valid. */
    private int confirmationTtlMinutes = 360;
    /** Base URL of the customer app hosting the accept/reject landing page. Sourced per-environment
     *  (env {@code CUSTOMER_LANDING_BASE_URL}) — never a prod URL baked in code. */
    private String customerLandingBaseUrl;
    /** Time zone the same-day cutoff and today/next-day framing are evaluated in. */
    private String zone = "Asia/Kolkata";

    public int getLastMileWindowMinutes() { return lastMileWindowMinutes; }
    public void setLastMileWindowMinutes(int v) { this.lastMileWindowMinutes = v; }
    public int getHubProcessingMinutes() { return hubProcessingMinutes; }
    public void setHubProcessingMinutes(int v) { this.hubProcessingMinutes = v; }
    public LocalTime getSameDayCutoff() { return sameDayCutoff; }
    public void setSameDayCutoff(LocalTime v) { this.sameDayCutoff = v; }
    public int getConfirmationTtlMinutes() { return confirmationTtlMinutes; }
    public void setConfirmationTtlMinutes(int v) { this.confirmationTtlMinutes = v; }
    public String getCustomerLandingBaseUrl() { return customerLandingBaseUrl; }
    public void setCustomerLandingBaseUrl(String v) { this.customerLandingBaseUrl = v; }
    public String getZone() { return zone; }
    public void setZone(String v) { this.zone = v; }
}
