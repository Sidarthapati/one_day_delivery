package com.oneday.hub.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.hub.domain.*;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.BagManifestRepository;
import com.oneday.hub.repository.DeliveryBagItemRepository;
import com.oneday.hub.repository.DeliveryBagRepository;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.DeliveryBagService;
import com.oneday.hub.service.exception.NoFreeStandException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryBagServiceImplTest {

    @Mock DeliveryBagRepository deliveryBagRepository;
    @Mock DeliveryBagItemRepository deliveryBagItemRepository;
    @Mock BagManifestRepository bagManifestRepository;
    @Mock StandRepository standRepository;
    @Mock HubEventProducer eventProducer;

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-27T10:00:00Z"), ZoneOffset.UTC);

    private DeliveryBagServiceImpl service() {
        return new DeliveryBagServiceImpl(deliveryBagRepository, deliveryBagItemRepository,
                bagManifestRepository, standRepository, eventProducer, new ObjectMapper(), clock);
    }

    private final UUID hubId = UUID.randomUUID();
    private final UUID standId = UUID.randomUUID();
    private final UUID loopId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 6, 27);

    private Stand freeStand() {
        return Stand.builder().id(standId).cityId(hubId).hubId(hubId).standNo("D-1")
                .capacity(200).status(StandStatus.OPEN).build();
    }

    private DeliveryBagService.OpenDeliveryBagCommand routeCmd() {
        return new DeliveryBagService.OpenDeliveryBagCommand(hubId, hubId, BagKind.ROUTE, date,
                UUID.randomUUID(), loopId, null, null);
    }

    @Test
    void openBag_lazyCreate_allocatesFreeDeliveryStand_andEmitsBagCreated() {
        when(deliveryBagRepository.findByLoopIdAndBagDateAndStatus(loopId, date, DeliveryBagStatus.OPEN))
                .thenReturn(Optional.empty());
        when(standRepository.findFreeStands(hubId, StandStatus.OPEN, "DELIVERY_DOCK"))
                .thenReturn(List.of(freeStand()));
        when(deliveryBagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeliveryBag bag = service().openBag(routeCmd());

        assertThat(bag.getStatus()).isEqualTo(DeliveryBagStatus.OPEN);
        assertThat(bag.getCurrentStandId()).isEqualTo(standId);
        assertThat(bag.getBagKind()).isEqualTo(BagKind.ROUTE);
        verify(eventProducer).emitDeliveryBagCreated(any(DeliveryBag.class), eq("D-1"));
    }

    @Test
    void openBag_reuseOpenBag_doesNotAllocateAnotherStand() {
        DeliveryBag existing = DeliveryBag.builder().id(UUID.randomUUID()).cityId(hubId).hubId(hubId)
                .bagKind(BagKind.ROUTE).loopId(loopId).currentStandId(standId).status(DeliveryBagStatus.OPEN).build();
        when(deliveryBagRepository.findByLoopIdAndBagDateAndStatus(loopId, date, DeliveryBagStatus.OPEN))
                .thenReturn(Optional.of(existing));

        DeliveryBag bag = service().openBag(routeCmd());

        assertThat(bag).isSameAs(existing);
        verify(standRepository, never()).findFreeStands(any(), any(), any());
        verify(eventProducer, never()).emitDeliveryBagCreated(any(), any());
    }

    @Test
    void openBag_noFreeStand_escalates() {
        when(deliveryBagRepository.findByLoopIdAndBagDateAndStatus(loopId, date, DeliveryBagStatus.OPEN))
                .thenReturn(Optional.empty());
        when(standRepository.findFreeStands(hubId, StandStatus.OPEN, "DELIVERY_DOCK"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service().openBag(routeCmd()))
                .isInstanceOf(NoFreeStandException.class);
    }

    @Test
    void addParcel_accumulatesWeightAndCount() {
        DeliveryBag bag = DeliveryBag.builder().id(UUID.randomUUID()).cityId(hubId).hubId(hubId)
                .bagKind(BagKind.ROUTE).loopId(loopId).currentStandId(standId)
                .status(DeliveryBagStatus.OPEN).parcelCount(0).weightGrams(0).build();
        when(deliveryBagRepository.findById(bag.getId())).thenReturn(Optional.of(bag));
        when(deliveryBagItemRepository.existsByParcelIdAndStatusIn(any(), any())).thenReturn(false);
        when(deliveryBagItemRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deliveryBagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ShipmentInfoPort.ParcelInfo parcel = new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "BLR-9",
                ShipmentState.AT_DEST_HUB, 1200, DropType.DA_DELIVERY, DeliveryType.INTERCITY,
                "DELHI", "MUMBAI", "400001", UUID.randomUUID(), null);

        service().addParcel(bag.getId(), parcel, parcel.destTileId(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(bag.getParcelCount()).isEqualTo(1);
        assertThat(bag.getWeightGrams()).isEqualTo(1200);
        ArgumentCaptor<DeliveryBagItem> item = ArgumentCaptor.forClass(DeliveryBagItem.class);
        verify(deliveryBagItemRepository).save(item.capture());
        assertThat(item.getValue().getStatus()).isEqualTo(DeliveryBagItemStatus.STAGED);
        assertThat(item.getValue().getDeliveryBagId()).isEqualTo(bag.getId());
    }
}
