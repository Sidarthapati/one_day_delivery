package com.oneday.hub.service.port;

import com.oneday.common.log.AuditLog;
import com.oneday.common.port.HubRecallPort;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.FlightBagItem;
import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.domain.FlightBagItemStatus;
import com.oneday.hub.repository.FlightBagItemRepository;
import com.oneday.hub.repository.FlightBagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Real M7 backing for {@link HubRecallPort} (mid-transit RTO). Resolves a shipment to its current
 * flight-bag item; if the bag is still {@code OPEN} the item is flipped {@code REMOVED} (append-only,
 * bag counts decremented) and the parcel is {@link RecallOutcome#PULLED_FROM_BAG}. A parcel not
 * currently in a bag is {@link RecallOutcome#NOT_BAGGED} (nothing to pull). A {@code SEALED}/
 * {@code DISPATCHED} bag has its manifest generated and AWB booked, so the parcel is
 * {@link RecallOutcome#COMMITTED} — it must fly and RTO from the destination hub. Never unseals a bag.
 */
@Component
class HubRecallAdapter implements HubRecallPort {

    private static final Logger log = LoggerFactory.getLogger(HubRecallAdapter.class);

    private final FlightBagItemRepository flightBagItemRepository;
    private final FlightBagRepository flightBagRepository;
    private final Clock clock;

    HubRecallAdapter(FlightBagItemRepository flightBagItemRepository,
                     FlightBagRepository flightBagRepository,
                     Clock clock) {
        this.flightBagItemRepository = flightBagItemRepository;
        this.flightBagRepository = flightBagRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RecallOutcome recallAtOrigin(UUID shipmentId) {
        FlightBagItem item = flightBagItemRepository
                .findFirstByParcelIdAndStatus(shipmentId, FlightBagItemStatus.IN_BAG)
                .orElse(null);
        if (item == null) {
            // Not bagged (not yet sorted, or already removed) — nothing to pull, safe to return.
            return RecallOutcome.NOT_BAGGED;
        }

        // Lock the bag row before reading its status and mutating counts, so a concurrent seal can't
        // slip in between the OPEN check and the pull (seal takes the same lock — see FlightBagServiceImpl).
        FlightBag bag = flightBagRepository.findByIdForUpdate(item.getBagId()).orElse(null);
        if (bag == null || bag.getStatus() != FlightBagStatus.OPEN) {
            // Sealed/dispatched/handed-over → manifest generated + AWB booked; must fly.
            return RecallOutcome.COMMITTED;
        }

        // OPEN bag → pull the parcel out (append-only: item → REMOVED, bag counts decremented).
        item.setStatus(FlightBagItemStatus.REMOVED);
        item.setRemovedAt(clock.instant());
        flightBagItemRepository.save(item);
        bag.setParcelCount(Math.max(0, bag.getParcelCount() - 1));
        bag.setWeightGrams(Math.max(0, bag.getWeightGrams() - item.getWeightGrams()));
        flightBagRepository.save(bag);

        AuditLog.event("bag.parcel_recalled")
                .kv("shipmentId", shipmentId)
                .kv("bagId", bag.getId())
                .kv("flightNo", bag.getFlightNo())
                .log();
        log.info("Recalled parcel {} from OPEN bag {} (flight {}) for mid-transit RTO",
                shipmentId, bag.getId(), bag.getFlightNo());
        return RecallOutcome.PULLED_FROM_BAG;
    }
}
