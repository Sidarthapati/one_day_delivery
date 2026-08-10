package com.oneday.orders.service;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of the last cleartext pickup <em>and</em> delivery OTP per shipment.
 *
 * <p>OTPs are BCrypt-hashed (cleartext is never persisted). This captures the cleartext at
 * generation time so the shipment owner / a field tester can read it in the business portal
 * ({@code /api/v1/shipments/mine/{ref}/pickup-otp}) or the dev peek
 * ({@code /internal/dev/shipments/{ref}/delivery-otp}) and read it to the associate — no SMS.
 * It's a cache, not a store: a restart empties it, and a resend/regenerate mints a fresh code.</p>
 *
 * <p>Pickup and delivery codes are kept in <b>separate</b> maps so they never collide on the same
 * {@code shipmentId} (one shipment has both, at different points in its life).</p>
 */
@Component
public class DevOtpRegistry {

    private final ConcurrentHashMap<UUID, String> latestPickup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> latestDelivery = new ConcurrentHashMap<>();

    public void put(UUID shipmentId, String otp) {
        latestPickup.put(shipmentId, otp);
    }

    public String get(UUID shipmentId) {
        return latestPickup.get(shipmentId);
    }

    public void putDelivery(UUID shipmentId, String otp) {
        latestDelivery.put(shipmentId, otp);
    }

    public String getDelivery(UUID shipmentId) {
        return latestDelivery.get(shipmentId);
    }
}
