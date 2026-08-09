package com.oneday.orders.service;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of the last cleartext pickup OTP per shipment.
 *
 * <p>Pickup OTPs are BCrypt-hashed (cleartext is never persisted). This captures the cleartext at
 * generation time so the shipment owner can read it in the business portal
 * ({@code /api/v1/shipments/mine/{ref}/pickup-otp}) and read it to the pickup associate — no SMS.
 * It's a cache, not a store: a restart empties it, and "Regenerate" mints a fresh code.</p>
 */
@Component
public class DevOtpRegistry {

    private final ConcurrentHashMap<UUID, String> latest = new ConcurrentHashMap<>();

    public void put(UUID shipmentId, String otp) {
        latest.put(shipmentId, otp);
    }

    public String get(UUID shipmentId) {
        return latest.get(shipmentId);
    }
}
