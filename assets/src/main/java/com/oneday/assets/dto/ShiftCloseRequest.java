package com.oneday.assets.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Close the asset registry for a station shift. {@code shift} is the label being closed (e.g. SHIFT_1 /
 * SHIFT_2); {@code date} defaults to today; {@code cityId} is required only for ADMIN (a manager is
 * pinned to their own station).
 */
public record ShiftCloseRequest(@NotBlank String shift, LocalDate date, UUID cityId) {
}
