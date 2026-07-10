package com.oneday.hub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Open/get the flight bag for a (flight, date, dest_hub) (§14.2 POST /hub/{hubId}/bags). The stand is
 * allocated dynamically from the hub's pool on first open — the caller does not choose it.
 */
public record OpenBagRequest(
        @NotBlank String flightNo,
        @NotNull LocalDate flightDate,
        @NotBlank String originHub,
        @NotBlank String destHub,
        Instant bagCutoff) {
}
