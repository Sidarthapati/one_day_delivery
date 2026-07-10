package com.oneday.hub.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.hub.domain.*;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.*;
import com.oneday.hub.service.FlightBagService;
import com.oneday.hub.service.exception.IllegalBagStateException;
import com.oneday.hub.service.port.BarcodePort;
import com.oneday.hub.service.port.ShipmentInfoPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightBagServiceImplTest {

    @Mock FlightBagRepository flightBagRepository;
    @Mock FlightBagItemRepository flightBagItemRepository;
    @Mock BagManifestRepository bagManifestRepository;
    @Mock StandRepository standRepository;
    @Mock StandReassignmentAuditRepository reassignmentAuditRepository;
    @Mock ShipmentInfoPort shipmentInfoPort;
    @Mock BarcodePort barcodePort;
    @Mock HubEventProducer eventProducer;

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-27T10:00:00Z"), ZoneOffset.UTC);

    private FlightBagServiceImpl service() {
        return new FlightBagServiceImpl(flightBagRepository, flightBagItemRepository, bagManifestRepository,
                standRepository, reassignmentAuditRepository, shipmentInfoPort, barcodePort,
                eventProducer, new ObjectMapper(), clock);
    }

    private final UUID hubId = UUID.randomUUID();
    private final UUID standId = UUID.randomUUID();

    private FlightBag openBag(UUID id) {
        return FlightBag.builder().id(id).cityId(hubId).hubId(hubId).flightNo("ODMUMBAI18")
                .flightDate(LocalDate.of(2026, 6, 27)).originHub("DELHI").destHub("MUMBAI")
                .currentStandId(standId).status(FlightBagStatus.OPEN).parcelCount(0).weightGrams(0).build();
    }

    private ShipmentInfoPort.ParcelInfo parcel(int weight) {
        return new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "BLR-1", ShipmentState.ORIGIN_HUB_PROCESSING,
                weight, DropType.DA_DELIVERY, DeliveryType.INTERCITY, "DELHI", "MUMBAI", "400001", null, null);
    }

    private FlightBagService.OpenBagCommand openCmd() {
        return new FlightBagService.OpenBagCommand(hubId, hubId, "ODMUMBAI18",
                LocalDate.of(2026, 6, 27), "DELHI", "MUMBAI", null);
    }

    private Stand freeStand() {
        return Stand.builder().id(standId).cityId(hubId).hubId(hubId).standNo("A-2")
                .capacity(200).status(StandStatus.OPEN).build();
    }

    @Test
    void openBag_lazyCreate_allocatesFreeStand_andEmitsBagCreated() {
        when(flightBagRepository.findByFlightNoAndFlightDateAndDestHubAndStatus(
                "ODMUMBAI18", LocalDate.of(2026, 6, 27), "MUMBAI", FlightBagStatus.OPEN)).thenReturn(Optional.empty());
        when(standRepository.findFreeStands(hubId, StandStatus.OPEN, "AIRPORT_DOCK"))
                .thenReturn(List.of(freeStand()));
        when(flightBagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        FlightBag bag = service().openBag(openCmd());

        assertThat(bag.getStatus()).isEqualTo(FlightBagStatus.OPEN);
        assertThat(bag.getCurrentStandId()).isEqualTo(standId);
        verify(eventProducer).emitBagCreated(any(FlightBag.class), eq("A-2"));
    }

    @Test
    void openBag_noFreeStand_escalates() {
        when(flightBagRepository.findByFlightNoAndFlightDateAndDestHubAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(standRepository.findFreeStands(hubId, StandStatus.OPEN, "AIRPORT_DOCK"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service().openBag(openCmd()))
                .isInstanceOf(com.oneday.hub.service.exception.NoFreeStandException.class);
        verify(flightBagRepository, never()).save(any());
    }

    @Test
    void openBag_existing_isIdempotent_noNewStandOrEvent() {
        FlightBag existing = openBag(UUID.randomUUID());
        when(flightBagRepository.findByFlightNoAndFlightDateAndDestHubAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.of(existing));

        FlightBag bag = service().openBag(openCmd());

        assertThat(bag).isSameAs(existing);
        verify(standRepository, never()).findFreeStands(any(), any(), any());
        verify(flightBagRepository, never()).save(any());
        verify(eventProducer, never()).emitBagCreated(any(), any());
    }

    @Test
    void addParcel_accumulatesWeightAndCount() {
        UUID bagId = UUID.randomUUID();
        FlightBag bag = openBag(bagId);
        when(flightBagRepository.findById(bagId)).thenReturn(Optional.of(bag));
        when(shipmentInfoPort.lookup("BLR-1")).thenReturn(Optional.of(parcel(1500)));
        when(flightBagItemRepository.existsByParcelIdAndStatus(any(), eq(FlightBagItemStatus.IN_BAG))).thenReturn(false);
        when(flightBagItemRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().addParcel(bagId, "BLR-1");

        assertThat(bag.getParcelCount()).isEqualTo(1);
        assertThat(bag.getWeightGrams()).isEqualTo(1500);
        verify(flightBagRepository).save(bag);
    }

    @Test
    void addParcel_toSealedBag_isRejected() {
        UUID bagId = UUID.randomUUID();
        FlightBag bag = openBag(bagId);
        bag.setStatus(FlightBagStatus.SEALED);
        when(flightBagRepository.findById(bagId)).thenReturn(Optional.of(bag));

        assertThatThrownBy(() -> service().addParcel(bagId, "BLR-1"))
                .isInstanceOf(IllegalBagStateException.class);
    }

    @Test
    void reassignStand_relabelsAndWritesAudit() {
        UUID bagId = UUID.randomUUID();
        UUID newStandId = UUID.randomUUID();
        FlightBag bag = openBag(bagId);
        when(flightBagRepository.findById(bagId)).thenReturn(Optional.of(bag));
        when(standRepository.findById(newStandId)).thenReturn(Optional.of(
                Stand.builder().id(newStandId).cityId(hubId).hubId(hubId).standNo("A-3")
                        .capacity(200).status(StandStatus.OPEN).build()));
        when(barcodePort.buildBagLabel("ODMUMBAI18", "A-3")).thenReturn("ODMUMBAI18|A-3");
        when(flightBagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().reassignStand(bagId, newStandId, UUID.randomUUID(), "stand full");

        assertThat(bag.getCurrentStandId()).isEqualTo(newStandId);
        ArgumentCaptor<StandReassignmentAudit> audit = ArgumentCaptor.forClass(StandReassignmentAudit.class);
        verify(reassignmentAuditRepository).save(audit.capture());
        assertThat(audit.getValue().getOldStandId()).isEqualTo(standId);
        assertThat(audit.getValue().getNewStandId()).isEqualTo(newStandId);
        assertThat(audit.getValue().getNewLabel()).isEqualTo("ODMUMBAI18|A-3");
    }

    @Test
    void seal_generatesManifest_andEmitsEvents() {
        UUID bagId = UUID.randomUUID();
        FlightBag bag = openBag(bagId);
        bag.setParcelCount(2);
        bag.setWeightGrams(3000);
        when(flightBagRepository.findById(bagId)).thenReturn(Optional.of(bag));
        when(flightBagItemRepository.findByBagIdAndStatus(bagId, FlightBagItemStatus.IN_BAG)).thenReturn(List.of(
                FlightBagItem.builder().bagId(bagId).parcelId(UUID.randomUUID()).shipmentRef("BLR-1").weightGrams(1000).status(FlightBagItemStatus.IN_BAG).build(),
                FlightBagItem.builder().bagId(bagId).parcelId(UUID.randomUUID()).shipmentRef("BLR-2").weightGrams(2000).status(FlightBagItemStatus.IN_BAG).build()));
        when(bagManifestRepository.save(any())).thenAnswer(i -> {
            BagManifest m = i.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        when(standRepository.findById(standId)).thenReturn(Optional.of(
                Stand.builder().id(standId).cityId(hubId).hubId(hubId).standNo("A-2")
                        .capacity(200).status(StandStatus.OPEN).build()));
        when(flightBagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        FlightBagService.SealResult result = service().seal(bagId);

        assertThat(result.bag().getStatus()).isEqualTo(FlightBagStatus.SEALED);
        assertThat(result.bag().getSealedAt()).isEqualTo(clock.instant());
        assertThat(result.manifest().getParcelCount()).isEqualTo(2);
        assertThat(result.manifest().getWeightGrams()).isEqualTo(3000);
        assertThat(result.manifest().getParcels()).contains("BLR-1", "BLR-2");
        verify(eventProducer).emitBagSealed(bag, "A-2");
        verify(eventProducer).emitManifestGenerated(eq(bag), any(BagManifest.class));
    }

    @Test
    void dispatch_requiresSealed() {
        UUID bagId = UUID.randomUUID();
        FlightBag bag = openBag(bagId);   // OPEN
        when(flightBagRepository.findById(bagId)).thenReturn(Optional.of(bag));

        assertThatThrownBy(() -> service().dispatch(bagId)).isInstanceOf(IllegalBagStateException.class);
    }

    @Test
    void dispatch_sealedBag_marksDispatched() {
        UUID bagId = UUID.randomUUID();
        FlightBag bag = openBag(bagId);
        bag.setStatus(FlightBagStatus.SEALED);
        when(flightBagRepository.findById(bagId)).thenReturn(Optional.of(bag));
        when(flightBagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        FlightBag dispatched = service().dispatch(bagId);

        assertThat(dispatched.getStatus()).isEqualTo(FlightBagStatus.DISPATCHED);
        assertThat(dispatched.getDispatchedAt()).isEqualTo(clock.instant());
    }
}
