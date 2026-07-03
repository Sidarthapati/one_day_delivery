package com.oneday.hub.service;

import com.oneday.common.kafka.enums.FlightReassignReason;
import com.oneday.hub.domain.FlightBag;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Executes an M9-decided flight move (§9, M7-D-006). M7 does not decide reschedules — it re-points
 * bags when M9 emits {@code FLIGHT_REASSIGNED}: pull the named parcels (or the whole from-flight bag)
 * out of their current flight bags, add them to the target flight's bag (lazily opened → allocates a
 * stand), regenerate the superseding manifest, cancel any emptied source bag, and emit
 * {@code BAG_RESCHEDULED}. Keyed on <b>flight number</b>; M7 owns the flight→bag(s) resolution.
 */
public interface BagReassignmentService {

    ReassignResult reassign(FlightReassignmentCommand command);

    /**
     * @param parcelIds   move exactly these parcels; if null/empty, move the whole open bag for
     *                    {@code fromFlightNo}.
     * @param fromFlightNo the flight the parcels are leaving (required when {@code parcelIds} is empty).
     */
    record FlightReassignmentCommand(
            String toFlightNo,
            LocalDate toFlightDate,
            String destHub,
            Instant newCutoff,
            String fromFlightNo,
            List<UUID> parcelIds,
            FlightReassignReason reason) {
    }

    record ReassignResult(FlightBag targetBag, int movedCount, UUID manifestId, String standNo) {
    }
}
