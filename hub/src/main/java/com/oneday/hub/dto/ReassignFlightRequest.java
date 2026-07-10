package com.oneday.hub.dto;

import com.oneday.common.kafka.enums.FlightReassignReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Imperative form of a FLIGHT_REASSIGNED event (§9) — the ops/test door to the executor. Move
 * {@code parcelIds} (or the whole {@code fromFlightNo} bag when they are omitted) onto the target flight.
 */
public record ReassignFlightRequest(
        @NotBlank String toFlightNo,
        @NotNull LocalDate toFlightDate,
        @NotBlank String destHub,
        Instant newCutoff,
        String fromFlightNo,
        List<UUID> parcelIds,
        @NotNull FlightReassignReason reason) {
}
