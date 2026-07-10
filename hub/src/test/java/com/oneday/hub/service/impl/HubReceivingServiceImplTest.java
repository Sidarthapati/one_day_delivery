package com.oneday.hub.service.impl;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.hub.domain.ArrivalMode;
import com.oneday.hub.domain.BagKind;
import com.oneday.hub.domain.DeliveryBagItem;
import com.oneday.hub.domain.DeliveryBagItemStatus;
import com.oneday.hub.domain.InboundReceipt;
import com.oneday.hub.domain.SortDirection;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.DeliveryBagItemRepository;
import com.oneday.hub.repository.InboundReceiptRepository;
import com.oneday.hub.service.HubReceivingService;
import com.oneday.hub.service.SortService;
import com.oneday.hub.service.exception.ParcelNotFoundException;
import com.oneday.hub.service.port.ShipmentInfoPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HubReceivingServiceImplTest {

    @Mock ShipmentInfoPort shipmentInfoPort;
    @Mock InboundReceiptRepository inboundReceiptRepository;
    @Mock DeliveryBagItemRepository deliveryBagItemRepository;
    @Mock SortService sortService;
    @Mock HubEventProducer eventProducer;

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-27T08:00:00Z"), ZoneOffset.UTC);

    private HubReceivingServiceImpl service() {
        return new HubReceivingServiceImpl(shipmentInfoPort, inboundReceiptRepository,
                deliveryBagItemRepository, sortService, eventProducer, clock);
    }

    private final UUID hubId = UUID.randomUUID();

    private ShipmentInfoPort.ParcelInfo vanParcel() {
        return new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "BLR-1", ShipmentState.AT_ORIGIN_HUB,
                1500, DropType.DA_DELIVERY, DeliveryType.INTERCITY, "DELHI", "MUMBAI", "400001", null, null);
    }

    private ShipmentInfoPort.ParcelInfo airportParcel(DropType dropType) {
        return new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "BLR-1", ShipmentState.AT_DEST_HUB,
                1500, dropType, DeliveryType.INTERCITY, "DELHI", "MUMBAI", "400001", UUID.randomUUID(), null);
    }

    private ShipmentInfoPort.ParcelInfo sameCityParcel() {
        return new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "BLR-1", ShipmentState.AT_ORIGIN_HUB,
                1500, DropType.DA_DELIVERY, DeliveryType.SAME_CITY, "MUMBAI", "MUMBAI", "400001", UUID.randomUUID(), null);
    }

    private void stubReceiptSave() {
        when(inboundReceiptRepository.save(any())).thenAnswer(i -> {
            InboundReceipt r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
    }

    private SortService.InboundSortResult inboundResult(ShipmentInfoPort.ParcelInfo p) {
        return new SortService.InboundSortResult(p.shipmentId(), p.shipmentRef(), p.destTileId(),
                BagKind.ROUTE, UUID.randomUUID(), UUID.randomUUID(), "D-1",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DropType.DA_DELIVERY);
    }

    @Test
    void receive_van_recordsOutboundReceipt_andSorts() {
        ShipmentInfoPort.ParcelInfo parcel = vanParcel();
        when(shipmentInfoPort.lookup("BLR-1")).thenReturn(Optional.of(parcel));
        stubReceiptSave();
        SortService.SortResult sort = new SortService.SortResult(parcel.shipmentId(), "BLR-1", "MUMBAI",
                UUID.randomUUID(), UUID.randomUUID(), "A-2", "ODMUMBAI12", null,
                "DELHI", "MUMBAI", null, null);
        when(sortService.resolveOutbound(eq(hubId), eq(parcel), any())).thenReturn(sort);

        HubReceivingService.ReceiveResult result = service().receive(hubId, "BLR-1");

        assertThat(result.sort().standNo()).isEqualTo("A-2");
        assertThat(result.inboundSort()).isNull();
        ArgumentCaptor<InboundReceipt> receipt = ArgumentCaptor.forClass(InboundReceipt.class);
        verify(inboundReceiptRepository).save(receipt.capture());
        assertThat(receipt.getValue().getArrivalMode()).isEqualTo(ArrivalMode.VAN);
        assertThat(receipt.getValue().getDirection()).isEqualTo(SortDirection.OUTBOUND);
        verify(eventProducer, never()).emitSameCityOutbound(any(), any(), any());
    }

    @Test
    void receive_airport_daDelivery_recordsInboundReceipt_andResolvesInbound() {
        ShipmentInfoPort.ParcelInfo parcel = airportParcel(DropType.DA_DELIVERY);
        when(shipmentInfoPort.lookup("BLR-1")).thenReturn(Optional.of(parcel));
        stubReceiptSave();
        when(sortService.resolveInbound(eq(hubId), eq(parcel), any())).thenReturn(inboundResult(parcel));

        HubReceivingService.ReceiveResult result = service().receive(hubId, "BLR-1");

        assertThat(result.inboundSort()).isNotNull();
        assertThat(result.inboundSort().bagKind()).isEqualTo(BagKind.ROUTE);
        assertThat(result.sort()).isNull();
        ArgumentCaptor<InboundReceipt> receipt = ArgumentCaptor.forClass(InboundReceipt.class);
        verify(inboundReceiptRepository).save(receipt.capture());
        assertThat(receipt.getValue().getArrivalMode()).isEqualTo(ArrivalMode.AIRPORT);
        assertThat(receipt.getValue().getDirection()).isEqualTo(SortDirection.INBOUND);
        verify(deliveryBagItemRepository, never()).save(any());
    }

    @Test
    void receive_airport_hubCollect_placesOnShelf_noSort() {
        ShipmentInfoPort.ParcelInfo parcel = airportParcel(DropType.HUB_COLLECT);
        when(shipmentInfoPort.lookup("BLR-1")).thenReturn(Optional.of(parcel));
        stubReceiptSave();

        HubReceivingService.ReceiveResult result = service().receive(hubId, "BLR-1");

        assertThat(result.sort()).isNull();
        assertThat(result.inboundSort()).isNull();
        ArgumentCaptor<DeliveryBagItem> item = ArgumentCaptor.forClass(DeliveryBagItem.class);
        verify(deliveryBagItemRepository).save(item.capture());
        assertThat(item.getValue().getStatus()).isEqualTo(DeliveryBagItemStatus.ON_SHELF);
        assertThat(item.getValue().getDropType()).isEqualTo(DropType.HUB_COLLECT);
        verifyNoInteractions(sortService);
    }

    @Test
    void receive_sameCity_skipsFlight_emitsSameCityOutbound_andResolvesInbound() {
        ShipmentInfoPort.ParcelInfo parcel = sameCityParcel();
        when(shipmentInfoPort.lookup("BLR-1")).thenReturn(Optional.of(parcel));
        stubReceiptSave();
        when(sortService.resolveInbound(eq(hubId), eq(parcel), any())).thenReturn(inboundResult(parcel));

        HubReceivingService.ReceiveResult result = service().receive(hubId, "BLR-1");

        assertThat(result.inboundSort()).isNotNull();
        verify(eventProducer).emitSameCityOutbound(parcel.shipmentId(), hubId, hubId);
        verify(sortService, never()).resolveOutbound(any(), any(), any());
    }

    @Test
    void receive_unknownParcel_throws() {
        when(shipmentInfoPort.lookup("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().receive(hubId, "NOPE"))
                .isInstanceOf(ParcelNotFoundException.class);
    }
}
