package com.oneday.hub.service.impl;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.hub.domain.ArrivalMode;
import com.oneday.hub.domain.InboundReceipt;
import com.oneday.hub.repository.InboundReceiptRepository;
import com.oneday.hub.service.HubReceivingService;
import com.oneday.hub.service.SortService;
import com.oneday.hub.service.exception.ParcelNotFoundException;
import com.oneday.hub.service.exception.UnsupportedArrivalModeException;
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
    @Mock SortService sortService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-27T08:00:00Z"), ZoneOffset.UTC);

    private HubReceivingServiceImpl service() {
        return new HubReceivingServiceImpl(shipmentInfoPort, inboundReceiptRepository, sortService, clock);
    }

    private final UUID hubId = UUID.randomUUID();

    private ShipmentInfoPort.ParcelInfo parcel() {
        return new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "BLR-1", ShipmentState.AT_ORIGIN_HUB,
                1500, DropType.DA_DELIVERY, DeliveryType.INTERCITY, "DELHI", "MUMBAI", "400001", null);
    }

    private ShipmentInfoPort.ParcelInfo landedParcel() {
        return new ShipmentInfoPort.ParcelInfo(UUID.randomUUID(), "BLR-1", ShipmentState.LANDED,
                1500, DropType.DA_DELIVERY, DeliveryType.INTERCITY, "DELHI", "MUMBAI", "400001", null);
    }

    @Test
    void receive_van_recordsReceipt_andSorts() {
        ShipmentInfoPort.ParcelInfo parcel = parcel();
        when(shipmentInfoPort.lookup("BLR-1")).thenReturn(Optional.of(parcel));
        when(inboundReceiptRepository.save(any())).thenAnswer(i -> {
            InboundReceipt r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        SortService.SortResult sort = new SortService.SortResult(parcel.shipmentId(), "BLR-1", "MUMBAI",
                UUID.randomUUID(), UUID.randomUUID(), "A-2", "ODMUMBAI12", LocalDate.of(2026, 6, 27),
                "DELHI", "MUMBAI",
                Instant.parse("2026-06-27T12:00:00Z"), Instant.parse("2026-06-27T14:00:00Z"));
        when(sortService.resolveOutbound(eq(hubId), eq(parcel), any())).thenReturn(sort);

        HubReceivingService.ReceiveResult result = service().receive(hubId, "BLR-1");

        assertThat(result.reconciled()).isTrue();
        assertThat(result.sort().standNo()).isEqualTo("A-2");
        ArgumentCaptor<InboundReceipt> receipt = ArgumentCaptor.forClass(InboundReceipt.class);
        verify(inboundReceiptRepository).save(receipt.capture());
        assertThat(receipt.getValue().getArrivalMode()).isEqualTo(ArrivalMode.VAN);
        assertThat(receipt.getValue().getHubId()).isEqualTo(hubId);
    }

    @Test
    void receive_airport_isUnsupportedInPr1() {
        // A landed parcel derives mode=AIRPORT (destination hub), which PR #1 doesn't handle yet.
        when(shipmentInfoPort.lookup("BLR-1")).thenReturn(Optional.of(landedParcel()));
        assertThatThrownBy(() -> service().receive(hubId, "BLR-1"))
                .isInstanceOf(UnsupportedArrivalModeException.class);
        verifyNoInteractions(sortService);
    }

    @Test
    void receive_unknownParcel_throws() {
        when(shipmentInfoPort.lookup("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().receive(hubId, "NOPE"))
                .isInstanceOf(ParcelNotFoundException.class);
    }
}
