package com.oneday.dispatch.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Station-manager request to mark one or more DAs absent for the current shift (midday). The reason
 * is recorded on the audit trail; the response previews the reassignment plan before it is applied.
 */
public record MarkAbsentRequest(
        @NotEmpty(message = "At least one DA id is required") List<@NotNull UUID> daIds,
        @Size(max = 500, message = "Reason must be at most 500 characters") String reason,
        // Optional — a STATION_MANAGER is scoped to their own city; ADMIN must pass the target city.
        UUID cityId) {
}
