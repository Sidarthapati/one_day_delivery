package com.oneday.hub.service.impl;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.hub.domain.*;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.FlightBagService;
import com.oneday.hub.service.DeliveryBagService;
import com.oneday.hub.service.SortService;
import com.oneday.hub.service.port.DeliveryRoutePort;
import com.oneday.hub.service.port.FlightAssignmentPort;
import com.oneday.hub.service.port.ShipmentInfoPort;
import com.oneday.hub.service.port.TerritoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SortServiceImplTest {

    @Mock FlightAssignmentPort flightAssignmentPort;
    @Mock FlightBagService flightBagService;
    @Mock DeliveryBagService deliveryBagService;
    @Mock TerritoryPort territoryPort;
    @Mock DeliveryRoutePort deliveryRoutePort;
    @Mock StandRepository standRepository;
    @Mock HubEventProducer eventProducer;

    @InjectMocks SortServiceImpl sortService;

    private final UUID hubId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-06-28T08:00:00Z");

    private ShipmentInfoPort.ParcelInfo parcel() {
        return new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "DEL-20260628-000001",
                ShipmentState.AT_ORIGIN_HUB, 1500, DropType.DA_DELIVERY, DeliveryType.INTERCITY,
                "DELHI", "CHENNAI", "600001", null, null);
    }

    private ShipmentInfoPort.ParcelInfo landedParcel(UUID destHex) {
        return new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "DEL-20260628-000002",
                ShipmentState.AT_DEST_HUB, 1500, DropType.DA_DELIVERY, DeliveryType.INTERCITY,
                "DELHI", "CHENNAI", "600001", destHex, null);
    }

    @Test
    void resolveOutbound_opensFlightBag_resolvesItsStand_andEmitsStandAssigned() {
        UUID bagId = UUID.randomUUID();
        UUID standId = UUID.randomUUID();
        Stand stand = Stand.builder().id(standId).cityId(hubId).hubId(hubId).standNo("A-3")
                .capacity(200).status(StandStatus.OPEN).build();
        FlightBag bag = FlightBag.builder().id(bagId).cityId(hubId).hubId(hubId).flightNo("ODCHENNAI12")
                .flightDate(LocalDate.of(2026, 6, 28)).originHub("DELHI").destHub("CHENNAI")
                .currentStandId(standId).status(FlightBagStatus.OPEN).parcelCount(0).weightGrams(0).build();

        when(flightAssignmentPort.assignFlight("CHENNAI", now)).thenReturn(
                new FlightAssignmentPort.FlightAssignment("ODCHENNAI12", LocalDate.of(2026, 6, 28),
                        "CHENNAI", now.plusSeconds(3600), now.plusSeconds(10800)));
        when(flightBagService.openBag(any(FlightBagService.OpenBagCommand.class))).thenReturn(bag);
        when(standRepository.findById(standId)).thenReturn(Optional.of(stand));

        ShipmentInfoPort.ParcelInfo parcel = parcel();
        SortService.SortResult result = sortService.resolveOutbound(hubId, parcel, now);

        assertThat(result.standNo()).isEqualTo("A-3");
        assertThat(result.bagId()).isEqualTo(bagId);
        assertThat(result.sortKey()).isEqualTo("CHENNAI");
        assertThat(result.flightNo()).isEqualTo("ODCHENNAI12");

        // The bag is opened for this flight/dest; the stand comes from the bag, not a directory.
        ArgumentCaptor<FlightBagService.OpenBagCommand> cmd = ArgumentCaptor.forClass(FlightBagService.OpenBagCommand.class);
        verify(flightBagService).openBag(cmd.capture());
        assertThat(cmd.getValue().destHub()).isEqualTo("CHENNAI");
        assertThat(cmd.getValue().flightNo()).isEqualTo("ODCHENNAI12");
        verify(eventProducer).emitStandAssigned(parcel.shipmentId(), hubId, hubId, "A-3", "CHENNAI", SortDirection.OUTBOUND);
    }

    @Test
    void resolveInbound_routePresent_opensRouteBag_andEmitsSortedForDelivery() {
        UUID destHex = UUID.randomUUID();
        UUID territoryId = UUID.randomUUID();
        UUID routePlanId = UUID.randomUUID();
        UUID vanId = UUID.randomUUID();
        UUID bagId = UUID.randomUUID();
        UUID standId = UUID.randomUUID();
        Stand stand = Stand.builder().id(standId).cityId(hubId).hubId(hubId).standNo("D-1")
                .capacity(200).status(StandStatus.OPEN).build();
        DeliveryBag bag = DeliveryBag.builder().id(bagId).cityId(hubId).hubId(hubId).bagKind(BagKind.ROUTE)
                .routePlanId(routePlanId).vanId(vanId).currentStandId(standId)
                .status(DeliveryBagStatus.OPEN).build();

        when(territoryPort.territoryForHex(eq(hubId), eq(destHex), any()))
                .thenReturn(Optional.of(new TerritoryPort.DaTerritory(territoryId, null)));
        when(deliveryRoutePort.routeForTerritory(eq(hubId), eq(territoryId), any()))
                .thenReturn(Optional.of(new DeliveryRoutePort.DeliveryRoute(routePlanId, vanId)));
        when(deliveryBagService.openBag(any(DeliveryBagService.OpenDeliveryBagCommand.class))).thenReturn(bag);
        when(standRepository.findById(standId)).thenReturn(Optional.of(stand));

        ShipmentInfoPort.ParcelInfo parcel = landedParcel(destHex);
        SortService.InboundSortResult result = sortService.resolveInbound(hubId, parcel, now);

        assertThat(result.bagKind()).isEqualTo(BagKind.ROUTE);
        assertThat(result.vanId()).isEqualTo(vanId);
        assertThat(result.standNo()).isEqualTo("D-1");

        ArgumentCaptor<DeliveryBagService.OpenDeliveryBagCommand> cmd =
                ArgumentCaptor.forClass(DeliveryBagService.OpenDeliveryBagCommand.class);
        verify(deliveryBagService).openBag(cmd.capture());
        assertThat(cmd.getValue().bagKind()).isEqualTo(BagKind.ROUTE);
        assertThat(cmd.getValue().vanId()).isEqualTo(vanId);
        verify(deliveryBagService).addParcel(bagId, parcel, destHex, territoryId, routePlanId);
        verify(eventProducer).emitParcelSortedForDelivery(eq(parcel.shipmentId()), eq(hubId), eq(destHex),
                any(), eq(now), any(), eq(territoryId), eq(routePlanId), eq(vanId), eq(bagId), eq("D-1"));
        verify(eventProducer).emitDestSortComplete(eq(parcel.shipmentId()), eq(hubId), eq(hubId), any(), eq(now));
    }

    @Test
    void resolveInbound_noRoute_fallsBackToDaTerritoryBag() {
        UUID destHex = UUID.randomUUID();
        UUID territoryId = UUID.randomUUID();
        UUID bagId = UUID.randomUUID();
        UUID standId = UUID.randomUUID();
        Stand stand = Stand.builder().id(standId).cityId(hubId).hubId(hubId).standNo("D-2")
                .capacity(200).status(StandStatus.OPEN).build();
        DeliveryBag bag = DeliveryBag.builder().id(bagId).cityId(hubId).hubId(hubId).bagKind(BagKind.DA_TERRITORY)
                .daTerritoryId(territoryId).currentStandId(standId).status(DeliveryBagStatus.OPEN).build();

        when(territoryPort.territoryForHex(eq(hubId), eq(destHex), any()))
                .thenReturn(Optional.of(new TerritoryPort.DaTerritory(territoryId, null)));
        when(deliveryRoutePort.routeForTerritory(eq(hubId), eq(territoryId), any()))
                .thenReturn(Optional.empty());   // no van runs this territory
        when(deliveryBagService.openBag(any(DeliveryBagService.OpenDeliveryBagCommand.class))).thenReturn(bag);
        when(standRepository.findById(standId)).thenReturn(Optional.of(stand));

        ShipmentInfoPort.ParcelInfo parcel = landedParcel(destHex);
        SortService.InboundSortResult result = sortService.resolveInbound(hubId, parcel, now);

        assertThat(result.bagKind()).isEqualTo(BagKind.DA_TERRITORY);
        assertThat(result.vanId()).isNull();

        ArgumentCaptor<DeliveryBagService.OpenDeliveryBagCommand> cmd =
                ArgumentCaptor.forClass(DeliveryBagService.OpenDeliveryBagCommand.class);
        verify(deliveryBagService).openBag(cmd.capture());
        assertThat(cmd.getValue().bagKind()).isEqualTo(BagKind.DA_TERRITORY);
        assertThat(cmd.getValue().daTerritoryId()).isEqualTo(territoryId);
        verify(eventProducer).emitParcelSortedForDelivery(eq(parcel.shipmentId()), eq(hubId), eq(destHex),
                any(), eq(now), any(), eq(territoryId), isNull(), isNull(), eq(bagId), eq("D-2"));
    }
}
