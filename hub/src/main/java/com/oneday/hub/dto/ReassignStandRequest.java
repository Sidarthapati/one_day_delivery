package com.oneday.hub.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Stand-full move + relabel (§14.2 POST /hub/{hubId}/bags/{bagId}/reassign-stand, M7-D-008). */
public record ReassignStandRequest(
        @NotNull UUID newStandId,
        UUID actorId,
        String reason) {
}
