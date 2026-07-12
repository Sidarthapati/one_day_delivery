package com.oneday.barcode.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * "Generate Label" from the DA app at pickup. {@code destCity} is the destination hub IATA (e.g.
 * "DEL"); the app already shows it on the pickup task. {@code actorId} is the DA (ponytail: from the
 * request until PR3 wires the auth principal). {@code clientScanId} makes the call idempotent.
 */
public record LabelRequest(
        @NotNull UUID shipmentId,
        @NotBlank @Size(min = 3, max = 3) String destCity,
        UUID actorId,
        UUID clientScanId) {
}
