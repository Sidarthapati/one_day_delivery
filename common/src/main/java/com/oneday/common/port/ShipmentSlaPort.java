package com.oneday.common.port;

import com.oneday.common.domain.enums.SlaState;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Read shipments' authoritative M10 SLA status in bulk, without importing the sla module. Implemented in
 * sla (M10); consumed in dispatch (M5) so the DA control-tower can colour a DA's tasks by the real
 * per-shipment SLA state instead of a dispatch-local ETA heuristic. Batch (one {@code findByShipmentIdIn})
 * to avoid an N+1 over a DA's task list. Same cross-module pattern as {@link ShipmentRefPort}.
 */
public interface ShipmentSlaPort {

    /** SLA status per shipment id; ids without an SLA row yet (e.g. pre-pickup) are absent from the map. */
    Map<UUID, SlaStatus> slaFor(Collection<UUID> shipmentIds);

    /**
     * The cross-module SLA snapshot the control tower needs. Only {@code common}-safe types — the sla-only
     * {@code PriorityBand} is deliberately not exposed; the RAG needs the colour, not the triage band.
     *
     * @param state          overall SLA colour (GREEN/AMBER/RED/BREACHED/CLOSED)
     * @param breached        target actually passed
     * @param urgencyMinutes minutes projected (or already) past target; negative = slack; null before the
     *                        SLA clock starts
     * @param actByAt        soonest hard window the manager is racing; null before the clock starts
     */
    record SlaStatus(SlaState state, boolean breached, Integer urgencyMinutes, Instant actByAt) {
    }
}
