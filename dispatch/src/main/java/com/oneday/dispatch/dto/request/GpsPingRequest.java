package com.oneday.dispatch.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** A DA GPS heartbeat. {@code timestamp} is optional (defaults to now at the controller). */
public record GpsPingRequest(
        @NotNull Double lat,
        @NotNull Double lon,
        Instant timestamp) {
}
