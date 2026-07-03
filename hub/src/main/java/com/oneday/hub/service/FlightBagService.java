package com.oneday.hub.service;

import com.oneday.hub.domain.FlightBagItem;
import com.oneday.hub.domain.BagManifest;
import com.oneday.hub.domain.FlightBag;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Flight-bag lifecycle (§7.2–7.3): lazy open per (flight, date, dest_hub), add parcels with weight
 * accumulation, stand-overflow reassign + relabel (M7-D-008), seal → append-only manifest, dispatch.
 */
public interface FlightBagService {

    /**
     * Idempotently open (or fetch) the open bag for a (flight, date, dest_hub). On first open the
     * bag is allocated a free stand from the hub's pool (dynamic assignment); subsequent parcels for
     * the same flight reuse the existing open bag and its stand.
     */
    FlightBag openBag(OpenBagCommand command);

    /** Add a parcel to an open bag; accumulates weight + count (weight from M4 confirmed weight). */
    FlightBagItem addParcel(UUID bagId, String shipmentRef);

    /** Stand-full move: relabel via M8 + append a reassignment audit; the bag's stand pointer moves. */
    FlightBag reassignStand(UUID bagId, UUID newStandId, UUID actorId, String reason);

    /** Seal an open bag → generate the append-only manifest, freeze contents (BAG_SEALED + MANIFEST_GENERATED). */
    SealResult seal(UUID bagId);

    /** Dispatch a sealed bag to the airport shuttle. */
    FlightBag dispatch(UUID bagId);

    /** The current (latest) manifest for a bag. */
    BagManifest currentManifest(UUID bagId);

    /** Read a bag by id. */
    FlightBag bag(UUID bagId);

    record OpenBagCommand(
            UUID cityId,
            UUID hubId,
            String flightNo,
            LocalDate flightDate,
            String originHub,
            String destHub,
            Instant bagCutoff) {
    }

    record SealResult(FlightBag bag, BagManifest manifest) {
    }
}
