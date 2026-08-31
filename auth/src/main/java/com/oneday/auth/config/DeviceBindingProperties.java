package com.oneday.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Device-binding policy (anti-abuse Phase 4), bound under {@code godspeed.device-binding}. When
 * {@code single-active-device} is on, a fresh login from a role in {@code roles} revokes that user's
 * sessions on every other device — so a rented/shared DA login can't run on two handsets at once.
 * Off by default: it only bites once ops turn it on, and only for the listed roles.
 *
 * <pre>{@code
 * godspeed:
 *   device-binding:
 *     single-active-device: true
 *     roles: [DELIVERY_ASSOCIATE]
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "godspeed.device-binding")
public class DeviceBindingProperties {

    private boolean singleActiveDevice = false;
    private Set<String> roles = Set.of("DELIVERY_ASSOCIATE");

    /** True when the single-active-device sweep should run for this role. */
    public boolean appliesTo(String role) {
        return singleActiveDevice && role != null && roles.contains(role);
    }

    public boolean isSingleActiveDevice() { return singleActiveDevice; }
    public void setSingleActiveDevice(boolean singleActiveDevice) { this.singleActiveDevice = singleActiveDevice; }

    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) { this.roles = roles; }
}
