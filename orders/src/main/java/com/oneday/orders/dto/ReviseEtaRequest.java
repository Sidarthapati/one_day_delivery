package com.oneday.orders.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Ops/system request to revise a shipment's delivery ETA (e.g. after a hub recompute or a flight
 * reassignment). If the new ETA is later than what was promised at booking, the customer is notified.
 *
 * @param newEta the revised expected-delivery instant (required)
 * @param reason optional free-text note for the audit trail / the customer-facing context
 */
public record ReviseEtaRequest(
        @NotNull Instant newEta,
        String reason) {
}
