package com.oneday.routing.dto;

/**
 * Lightweight reply to a telemetry POST. Most pings just ack {@code RECORDED}; an ARRIVED ping echoes
 * the computed {@code minutesLate} (0 if on time, null if no plan to compare against) so the driver
 * app can show "8 min late" without a second call.
 */
public record TelemetryAck(TelemetryType type, String status, Integer minutesLate) {

    public static TelemetryAck ok(TelemetryType type) {
        return new TelemetryAck(type, "RECORDED", null);
    }

    public static TelemetryAck arrived(Integer minutesLate) {
        return new TelemetryAck(TelemetryType.ARRIVED_AT_STOP, "RECORDED", minutesLate);
    }

    public static TelemetryAck scan(TelemetryType type, String custodyStatus) {
        return new TelemetryAck(type, custodyStatus, null);
    }
}
