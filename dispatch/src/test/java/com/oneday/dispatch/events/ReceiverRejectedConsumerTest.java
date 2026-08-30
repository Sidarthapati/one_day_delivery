package com.oneday.dispatch.events;

import com.oneday.common.kafka.events.ReceiverRejectedEvent;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DeferReason;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.grid.dto.response.ServiceableAtResponse;
import com.oneday.grid.service.GridService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiverRejectedConsumerTest {

    private DispatchService dispatchService;
    private DaTaskService daTaskService;
    private GridService gridService;
    private ReceiverRejectedConsumer consumer;

    private final UUID cityId = UUID.randomUUID();
    private final UUID tileId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatchService = mock(DispatchService.class);
        daTaskService = mock(DaTaskService.class);
        gridService = mock(GridService.class);
        consumer = new ReceiverRejectedConsumer(dispatchService, daTaskService, gridService, new DispatchProperties());
    }

    @Test
    void reparksDeliveryForChosenShift() {
        UUID shipment = UUID.randomUUID();
        when(gridService.serviceableAt(anyDouble(), anyDouble()))
                .thenReturn(new ServiceableAtResponse(true, "bengaluru", cityId, UUID.randomUUID(), "h3"));

        consumer.onReceiverRejected(new ReceiverRejectedEvent(shipment, "1DD-SHP", orderId, "1DD-ORD", "SHIFT_2",
                12.97, 77.61, tileId));

        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        verify(dispatchService).deferDeliveryForRetry(eq(shipment), eq(cityId), eq(tileId), eq(12.97), eq(77.61),
                eq(orderId), eq("1DD-ORD"), eq(tomorrow), eq("SHIFT_2"), eq(DeferReason.RECEIVER_REJECTED));
    }

    @Test
    void missingCoordinatesIsNoOp() {
        consumer.onReceiverRejected(new ReceiverRejectedEvent(UUID.randomUUID(), "1DD-SHP", orderId, "1DD-ORD", "SHIFT_1",
                null, null, tileId));
        verify(dispatchService, never()).deferDeliveryForRetry(any(), any(), any(), anyDouble(), anyDouble(),
                any(), any(), any(), any(), any());
    }
}
