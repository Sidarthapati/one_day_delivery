package com.oneday.dispatch.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * A DA GPS heartbeat. {@code timestamp} is the device fix time (optional; defaults to now at the
 * controller). The trust fields are optional so older app builds keep working: {@code accuracy}/
 * {@code speed} are the device's own readings; {@code mocked} is the Android mock-location flag the
 * app forwards from {@code expo-location} (true ⇒ the fix came from a fake-GPS provider).
 */
public record GpsPingRequest(
        @NotNull Double lat,
        @NotNull Double lon,
        Instant timestamp,
        Double accuracy,
        Double speed,
        Boolean mocked) {
}
