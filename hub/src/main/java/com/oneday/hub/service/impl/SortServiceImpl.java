package com.oneday.hub.service.impl;

import com.oneday.hub.config.ClockConfig;
import com.oneday.hub.domain.BagKind;
import com.oneday.hub.domain.DeliveryBag;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.SortDirection;
import com.oneday.hub.domain.Stand;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.BagService;
import com.oneday.hub.service.DeliveryBagService;
import com.oneday.hub.service.SortService;
import com.oneday.hub.service.exception.StandNotFoundException;
import com.oneday.hub.service.exception.UnresolvedDestinationException;
import com.oneday.hub.service.port.DeliveryRoutePort;
import com.oneday.hub.service.port.FlightAssignmentPort;
import com.oneday.hub.service.port.ShipmentInfoPort;
import com.oneday.hub.service.port.TerritoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The symmetric sort with dynamic stand assignment (M7-D-001). OUTBOUND (§7.1/M7-D-003): assign the
 * flight (M9 via port), open that flight's bag — lazily grabbing a free stand or reusing the open
 * bag's. INBOUND (§8.1/M7-D-012): walk the {@code hex → DA territory → delivery route} ladder, open
 * a ROUTE bag (van runs) or DA-TERRITORY bag (no van) on a free delivery stand, stage the parcel,
 * and emit the M6 feed. No pre-seeded directory either way; the open bag is the live directory.
 */
@Service
class SortServiceImpl implements SortService {

    private final FlightAssignmentPort flightAssignmentPort;
    private final BagService bagService;
    private final DeliveryBagService deliveryBagService;
    private final TerritoryPort territoryPort;
    private final DeliveryRoutePort deliveryRoutePort;
    private final StandRepository standRepository;
    private final HubEventProducer eventProducer;

    SortServiceImpl(FlightAssignmentPort flightAssignmentPort,
                    BagService bagService,
                    DeliveryBagService deliveryBagService,
                    TerritoryPort territoryPort,
                    DeliveryRoutePort deliveryRoutePort,
                    StandRepository standRepository,
                    HubEventProducer eventProducer) {
        this.flightAssignmentPort = flightAssignmentPort;
        this.bagService = bagService;
        this.deliveryBagService = deliveryBagService;
        this.territoryPort = territoryPort;
        this.deliveryRoutePort = deliveryRoutePort;
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

    @Override
    @Transactional
    public InboundSortResult resolveInbound(UUID hubId, ShipmentInfoPort.ParcelInfo parcel, Instant sortedAt) {
        UUID cityId = hubId;   // hub_id == city_id in v1
        UUID destHexId = parcel.destTileId();   // dest_hex from the label sort_key / M4 (M3 fallback later)
        if (destHexId == null) {
            throw new UnresolvedDestinationException(parcel.shipmentRef());
        }
        LocalDate validDate = sortedAt.atZone(ClockConfig.IST).toLocalDate();

        // Ladder rung 1 — hex → DA territory (always resolvable; missing = a data fault → escalate).
        TerritoryPort.DaTerritory territory = territoryPort.territoryForHex(cityId, destHexId, validDate)
                .orElseThrow(() -> new UnresolvedDestinationException(parcel.shipmentRef()));

        // Ladder rung 2 — DA territory → delivery route/loop. Empty = no van runs → DA-territory bag.
        Optional<DeliveryRoutePort.DeliveryRoute> route =
                deliveryRoutePort.routeForTerritory(cityId, territory.territoryId(), validDate);

        UUID daTerritoryId = territory.territoryId();
        UUID routePlanId = route.map(DeliveryRoutePort.DeliveryRoute::routePlanId).orElse(null);
        UUID loopId = route.map(DeliveryRoutePort.DeliveryRoute::loopId).orElse(null);
        BagKind kind = route.isPresent() ? BagKind.ROUTE : BagKind.DA_TERRITORY;

        DeliveryBagService.OpenDeliveryBagCommand cmd = route.isPresent()
                ? new DeliveryBagService.OpenDeliveryBagCommand(cityId, hubId, BagKind.ROUTE, validDate,
                        routePlanId, loopId, null, null)
                : new DeliveryBagService.OpenDeliveryBagCommand(cityId, hubId, BagKind.DA_TERRITORY, validDate,
                        null, null, daTerritoryId, territory.zoneId());

        DeliveryBag bag = deliveryBagService.openBag(cmd);
        deliveryBagService.addParcel(bag.getId(), parcel, destHexId, daTerritoryId, routePlanId);

        Stand stand = standRepository.findById(bag.getCurrentStandId())
                .orElseThrow(() -> new StandNotFoundException(bag.getCurrentStandId()));

        eventProducer.emitStandAssigned(parcel.shipmentId(), cityId, hubId,
                stand.getStandNo(), destHexId.toString(), SortDirection.INBOUND);
        // The M6 seam: per-parcel sorted-for-delivery feed (M6 binds to a loop) + the M10 leg signal.
        eventProducer.emitParcelSortedForDelivery(parcel.shipmentId(), cityId, destHexId, validDate,
                sortedAt, parcel.slaDeadline(), daTerritoryId, routePlanId, loopId, bag.getId(), stand.getStandNo());
        eventProducer.emitDestSortComplete(parcel.shipmentId(), cityId, hubId, validDate, sortedAt);

        return new InboundSortResult(parcel.shipmentId(), parcel.shipmentRef(), destHexId, kind,
                bag.getId(), stand.getId(), stand.getStandNo(), daTerritoryId, routePlanId, loopId,
                parcel.dropType());
    }
}
