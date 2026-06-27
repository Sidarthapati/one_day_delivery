package com.oneday.hub.service.impl;

import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.SortDirection;
import com.oneday.hub.domain.Stand;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.BagService;
import com.oneday.hub.service.SortService;
import com.oneday.hub.service.exception.StandNotFoundException;
import com.oneday.hub.service.port.FlightAssignmentPort;
import com.oneday.hub.service.port.ShipmentInfoPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * OUTBOUND sort with dynamic stand assignment (§7.1, M7-D-001/003): assign the flight (M9 via port),
 * then open that flight's bag — which lazily grabs a free stand from the hub's pool, or reuses the
 * stand of the already-open bag for this flight. Emits STAND_ASSIGNED → M4 advances the parcel to
 * ORIGIN_HUB_PROCESSING. No pre-seeded directory; the open {@code flight_bag} is the live directory.
 */
@Service
class SortServiceImpl implements SortService {

    private final FlightAssignmentPort flightAssignmentPort;
    private final BagService bagService;
    private final StandRepository standRepository;
    private final HubEventProducer eventProducer;

    SortServiceImpl(FlightAssignmentPort flightAssignmentPort,
                    BagService bagService,
                    StandRepository standRepository,
                    HubEventProducer eventProducer) {
        this.flightAssignmentPort = flightAssignmentPort;
        this.bagService = bagService;
        this.standRepository = standRepository;
        this.eventProducer = eventProducer;
    }

    @Override
    @Transactional
    public SortResult resolveOutbound(UUID hubId, ShipmentInfoPort.ParcelInfo parcel, Instant readyAt) {
        String destHub = parcel.destCity();   // OUTBOUND sort key = dest-hub code (M7-D-003)

        FlightAssignmentPort.FlightAssignment flight = flightAssignmentPort.assignFlight(destHub, readyAt);

        // Open (or find) the bag for this flight; first open allocates a free stand dynamically.
        FlightBag bag = bagService.openBag(new BagService.OpenBagCommand(
                hubId, hubId, flight.flightNo(), flight.flightDate(),
                parcel.originCity(), destHub, flight.bagCutoff()));

        Stand stand = standRepository.findById(bag.getCurrentStandId())
                .orElseThrow(() -> new StandNotFoundException(bag.getCurrentStandId()));

        eventProducer.emitStandAssigned(parcel.shipmentId(), stand.getCityId(), hubId,
                stand.getStandNo(), destHub, SortDirection.OUTBOUND);

        return new SortResult(parcel.shipmentId(), parcel.shipmentRef(), destHub,
                bag.getId(), stand.getId(), stand.getStandNo(),
                flight.flightNo(), flight.flightDate(), parcel.originCity(), flight.destHub(),
                flight.bagCutoff(), flight.arrival());
    }
}
