package com.oneday.orders.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev-only in-memory store of the last cleartext pickup OTP per shipment.
 *
 * <p>Pickup OTPs are BCrypt-hashed (cleartext is never persisted) and SMS is a log sink, so a field
 * tester has no way to read the code the DA must enter. Outside prod this captures the cleartext at
 * generation time so {@code DevOtpController} can echo it back. Never present in prod.</p>
 */
@Component
@Profile("!prod")
public class DevOtpRegistry {

    private final ConcurrentHashMap<UUID, String> latest = new ConcurrentHashMap<>();

    public void put(UUID shipmentId, String otp) {
        latest.put(shipmentId, otp);
    }

    public String get(UUID shipmentId) {
        return latest.get(shipmentId);
    }
}
