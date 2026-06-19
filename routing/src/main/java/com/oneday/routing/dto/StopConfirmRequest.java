package com.oneday.routing.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Body for {@code POST /routing/vans/{vanId}/stops/confirm} (§15 step 5 — "Confirm stop"). The app
 * has accumulated which parcels were actually handed over / taken / refused at this stop for one DA;
 * this fires the reconciliation (HandoffService): expected (manifest) vs these scanned sets →
 * MISSING / EXTRA / REJECTED, else HANDOFF_COMPLETED.
 */
public record StopConfirmRequest(
        Integer loopIndex,
        LocalDate date,
        Integer stopSeq,
        UUID daId,
        List<UUID> deliverScanned,
        List<UUID> collectScanned,
        List<UUID> rejected) {

    public LocalDate dateOrToday(LocalDate today) {
        return date != null ? date : today;
    }

    public Set<UUID> deliverSet() {
        return setOf(deliverScanned);
    }

    public Set<UUID> collectSet() {
        return setOf(collectScanned);
    }

    public Set<UUID> rejectedSet() {
        return setOf(rejected);
    }

    private static Set<UUID> setOf(List<UUID> ids) {
        return ids != null ? Set.copyOf(ids) : Set.of();
    }
}
