package com.oneday.hub.service.impl;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.hub.domain.*;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.BagService;
import com.oneday.hub.service.SortService;
import com.oneday.hub.service.port.FlightAssignmentPort;
import com.oneday.hub.service.port.ShipmentInfoPort;
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
    @Mock BagService bagService;
    @Mock StandRepository standRepository;
    @Mock HubEventProducer eventProducer;

    @InjectMocks SortServiceImpl sortService;

    private final UUID hubId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-06-28T08:00:00Z");

    private ShipmentInfoPort.ParcelInfo parcel() {
        return new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "DEL-20260628-000001",
                ShipmentState.AT_ORIGIN_HUB, 1500, DropType.DA_DELIVERY, DeliveryType.INTERCITY,
                "DELHI", "CHENNAI", "600001", null);
    }

    @Test
    void resolveOutbound_opensFlightBag_resolvesItsStand_andEmitsStandAssigned() {
        UUID bagId = UUID.randomUUID();
        UUID standId = UUID.randomUUID();
        Stand stand = Stand.builder().id(standId).cityId(hubId).hubId(hubId).standNo("A-3")
                .capacity(200).status(StandStatus.OPEN).build();
        FlightBag bag = FlightBag.builder().id(bagId).cityId(hubId).hubId(hubId).flightNo("ODCHENNAI12")
                .flightDate(LocalDate.of(2026, 6, 28)).originHub("DELHI").destHub("CHENNAI")
                .currentStandId(standId).status(BagStatus.OPEN).parcelCount(0).weightGrams(0).build();

        when(flightAssignmentPort.assignFlight("CHENNAI", now)).thenReturn(
                new FlightAssignmentPort.FlightAssignment("ODCHENNAI12", LocalDate.of(2026, 6, 28),
                        "CHENNAI", now.plusSeconds(3600), now.plusSeconds(10800)));
        when(bagService.openBag(any(BagService.OpenBagCommand.class))).thenReturn(bag);
        when(standRepository.findById(standId)).thenReturn(Optional.of(stand));

        ShipmentInfoPort.ParcelInfo parcel = parcel();
        SortService.SortResult result = sortService.resolveOutbound(hubId, parcel, now);

        assertThat(result.standNo()).isEqualTo("A-3");
        assertThat(result.bagId()).isEqualTo(bagId);
        assertThat(result.sortKey()).isEqualTo("CHENNAI");
        assertThat(result.flightNo()).isEqualTo("ODCHENNAI12");

        // The bag is opened for this flight/dest; the stand comes from the bag, not a directory.
        ArgumentCaptor<BagService.OpenBagCommand> cmd = ArgumentCaptor.forClass(BagService.OpenBagCommand.class);
        verify(bagService).openBag(cmd.capture());
        assertThat(cmd.getValue().destHub()).isEqualTo("CHENNAI");
        assertThat(cmd.getValue().flightNo()).isEqualTo("ODCHENNAI12");
        verify(eventProducer).emitStandAssigned(parcel.shipmentId(), hubId, hubId, "A-3", "CHENNAI", SortDirection.OUTBOUND);
    }
}
