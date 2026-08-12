package com.oneday.shuttle.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The shuttle agent's shared city queue: bags to carry to the airport and landed flights to collect
 * back. Both lists are identical for every agent in the city; completing an item removes it for all.
 */
public record ShuttleQueueResponse(List<OutboundBag> outbound, List<InboundFlight> inbound) {

    /**
     * A flight bag waiting to go to the airport. {@code status} is OPEN (hub still filling — not
     * loadable), SEALED (ready to load), or OVERDUE (leave-by passed, still at hub). Sorted by leaveBy.
     */
    public record OutboundBag(UUID bagId, String flightNo, LocalDate flightDate, String destHub,
                              int parcelCount, int weightGrams, Instant bagCutoff, Instant leaveBy,
                              String status) {
    }

    /** A landed flight whose parcels the agent must collect from the airport and bring to the hub. */
    public record InboundFlight(UUID awbId, String flightNo, LocalDate flightDate, String awbNo,
                                int parcelCount, Instant landedAt) {
    }
}
