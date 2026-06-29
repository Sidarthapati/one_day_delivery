package com.oneday.dispatch.events;

import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.ShipmentEventType;
import com.oneday.common.kafka.events.ShipmentCancelledEvent;
import com.oneday.common.kafka.events.ShipmentCreatedEvent;
import com.oneday.common.kafka.events.ShipmentStateChangedEvent;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.AssignmentResult;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.grid.dto.response.ServiceableAtResponse;
import com.oneday.grid.service.GridService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the shipment-events consumer's CREATED branch (pickup assignment). The broker,
 * DispatchService, queue repo and grid are mocked — this covers the routing/guards/resolution
 * branches; the FAILED-row reassignment semantics live in {@code DispatchQueueRepositoryTest}.
 */
class ShipmentEventsConsumerTest {

    private DispatchService dispatchService;
    private DispatchQueueRepository queueRepository;
    private GridService gridService;
    private ShipmentEventsConsumer consumer;

    private final UUID cityId = UUID.randomUUID();
    private final UUID tileId = UUID.randomUUID();
    private final UUID hexFromCoords = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatchService = mock(DispatchService.class);
        queueRepository = mock(DispatchQueueRepository.class);
        gridService = mock(GridService.class);
        consumer = new ShipmentEventsConsumer(dispatchService, queueRepository, gridService);

        lenient().when(queueRepository.findActiveByShipmentIdAndTaskType(any(), eq(TaskType.PICKUP)))
                .thenReturn(Optional.empty());
        lenient().when(gridService.serviceableAt(anyDouble(), anyDouble()))
                .thenReturn(new ServiceableAtResponse(true, "bengaluru", cityId, hexFromCoords, "h3idx"));
        lenient().when(dispatchService.assignPickup(any(), any(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(AssignmentResult.assigned(UUID.randomUUID(), 0));
    }

    @Test
    void daPickupTriggersAssignmentWithEventTileAndPaymentMode() {
        UUID shipment = UUID.randomUUID();
        consumer.onShipmentEvent(created(shipment, PickupType.DA_PICKUP, 12.97, 77.61, tileId, PaymentMode.PREPAID));

        verify(dispatchService).assignPickup(eq(shipment), eq(cityId), eq(12.97), eq(77.61), eq(tileId), eq("PREPAID"));
    }

    @Test
    void selfDropIsIgnored() {
        consumer.onShipmentEvent(created(UUID.randomUUID(), PickupType.SELF_DROP, 12.97, 77.61, tileId, PaymentMode.PREPAID));
        verifyNoInteractions(dispatchService);
    }

    @Test
    void duplicateWithActivePickupIsSkipped() {
        UUID shipment = UUID.randomUUID();
        when(queueRepository.findActiveByShipmentIdAndTaskType(eq(shipment), eq(TaskType.PICKUP)))
                .thenReturn(Optional.of(new DispatchQueue()));

        consumer.onShipmentEvent(created(shipment, PickupType.DA_PICKUP, 12.97, 77.61, tileId, PaymentMode.PREPAID));

        verify(dispatchService, never()).assignPickup(any(), any(), anyDouble(), anyDouble(), any(), any());
    }

    @Test
    void missingCoordinatesAreNotAssigned() {
        consumer.onShipmentEvent(created(UUID.randomUUID(), PickupType.DA_PICKUP, null, null, tileId, PaymentMode.COD));
        verify(dispatchService, never()).assignPickup(any(), any(), anyDouble(), anyDouble(), any(), any());
    }

    @Test
    void pointOutsideEveryGridIsNotAssigned() {
        when(gridService.serviceableAt(anyDouble(), anyDouble()))
                .thenReturn(new ServiceableAtResponse(false, null, null, null, null));

        consumer.onShipmentEvent(created(UUID.randomUUID(), PickupType.DA_PICKUP, 0.0, 0.0, tileId, PaymentMode.PREPAID));

        verify(dispatchService, never()).assignPickup(any(), any(), anyDouble(), anyDouble(), any(), any());
    }

    @Test
    void nullOriginTileIsResolvedFromCoordinates() {
        UUID shipment = UUID.randomUUID();
        consumer.onShipmentEvent(created(shipment, PickupType.DA_PICKUP, 12.97, 77.61, null, PaymentMode.PREPAID));

        verify(dispatchService).assignPickup(eq(shipment), eq(cityId), eq(12.97), eq(77.61), eq(hexFromCoords), eq("PREPAID"));
    }

    @Test
    void cancelledInPickupPhaseCancelsPickupTask() {
        UUID shipment = UUID.randomUUID();
        consumer.onShipmentEvent(cancelled(shipment, ShipmentState.PICKUP_ASSIGNED));
        verify(dispatchService).cancelTask(shipment, TaskType.PICKUP);
    }

    @Test
    void cancelledInDeliveryPhaseCancelsDeliveryTask() {
        UUID shipment = UUID.randomUUID();
        consumer.onShipmentEvent(cancelled(shipment, ShipmentState.DROP_ASSIGNED));
        verify(dispatchService).cancelTask(shipment, TaskType.DELIVERY);
    }

    @Test
    void cancelAfterPickupStillCancelsPickupTask() {
        // PICKED_UP is first-mile (not a delivery-phase state) → the pickup task is cancelled. cancelTask
        // now removes an IN_PROGRESS task from the DA's load too (RTO), so the consumer just delegates.
        UUID shipment = UUID.randomUUID();
        consumer.onShipmentEvent(cancelled(shipment, ShipmentState.PICKED_UP));
        verify(dispatchService).cancelTask(shipment, TaskType.PICKUP);
    }

    @Test
    void handedToDropVanDoesNotAssignDeliveryYet() {
        // Delivery assignment is blocked on the M4 contract (no dest coords) — must not be attempted.
        ShipmentStateChangedEvent e = new ShipmentStateChangedEvent();
        e.setEventType(ShipmentEventType.STATE_CHANGED);
        e.setShipmentId(UUID.randomUUID());
        e.setToState(ShipmentState.HANDED_TO_DROP_VAN);

        consumer.onShipmentEvent(e);

        verify(dispatchService, never()).assignDelivery(any(), any(), anyDouble(), anyDouble(), any());
    }

    private ShipmentCancelledEvent cancelled(UUID shipmentId, ShipmentState at) {
        ShipmentCancelledEvent e = new ShipmentCancelledEvent();
        e.setEventType(ShipmentEventType.CANCELLED);
        e.setShipmentId(shipmentId);
        e.setCancelledAtState(at);
        return e;
    }

    private ShipmentCreatedEvent created(UUID shipmentId, PickupType pickupType, Double lat, Double lon,
                                         UUID originTileId, PaymentMode paymentMode) {
        ShipmentCreatedEvent e = new ShipmentCreatedEvent();
        e.setEventType(ShipmentEventType.CREATED);
        e.setShipmentId(shipmentId);
        e.setPickupType(pickupType);
        e.setOriginLat(lat);
        e.setOriginLon(lon);
        e.setOriginTileId(originTileId);
        e.setPaymentMode(paymentMode);
        return e;
    }
}
