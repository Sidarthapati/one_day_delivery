package com.oneday.orders.events;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.ShipmentEventType;
import com.oneday.common.kafka.events.ShipmentCancelledEvent;
import com.oneday.common.kafka.events.ShipmentCreatedEvent;
import com.oneday.common.kafka.events.ShipmentStateChangedEvent;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.ParcelOrder;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.enums.TriggerSource;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentEventProducerTest {

    @Mock EventPublisher eventPublisher;
    @Mock ShipmentRepository shipmentRepository;
    @Mock ParcelOrderRepository parcelOrderRepository;
    @InjectMocks ShipmentEventProducer producer;

    private static Address addr(String line1, double lat, double lon) {
        Address a = new Address();
        a.setLine1(line1);
        a.setLatitude(lat);
        a.setLongitude(lon);
        return a;
    }

    @Test
    void transitioned_enrichesDropAssignment_withDestAndOrderRef() {
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Shipment s = new Shipment();
        s.setDestTileId(UUID.randomUUID());
        s.setDropType(DropType.DA_DELIVERY);
        s.setDestAddress(addr("B", 19.0, 72.8));
        s.setOrderId(orderId);
        when(shipmentRepository.findById(shipmentId)).thenReturn(Optional.of(s));
        ParcelOrder order = new ParcelOrder();
        ReflectionTestUtils.setField(order, "orderRef", "ORD-1");
        when(parcelOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        producer.onShipmentTransitioned(new ShipmentTransitioned(shipmentId, "REF", ShipmentState.DEST_HUB_PROCESSING,
                ShipmentState.HANDED_TO_DROP_VAN, "sys", TriggerSource.API, Instant.now()));

        ShipmentStateChangedEvent ev = capturePublished(ShipmentStateChangedEvent.class);
        assertThat(ev.getEventType()).isEqualTo(ShipmentEventType.STATE_CHANGED);
        assertThat(ev.getDestLat()).isEqualTo(19.0);
        assertThat(ev.getOrderRef()).isEqualTo("ORD-1");
        assertThat(ev.getTriggerSource()).isEqualTo("API");
    }

    @Test
    void transitioned_nonDropState_skipsEnrichment_andNullTriggerSource() {
        producer.onShipmentTransitioned(new ShipmentTransitioned(UUID.randomUUID(), "REF",
                ShipmentState.BOOKED, ShipmentState.PICKED_UP, "sys", null, null)); // null source + null occurredAt
        ShipmentStateChangedEvent ev = capturePublished(ShipmentStateChangedEvent.class);
        assertThat(ev.getTriggerSource()).isNull();
        assertThat(ev.getOccurredAt()).isNotNull();
        verify(shipmentRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void booked_fullShipment_mapsAllFields() {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setShipmentRef("REF");
        s.setCustomerType(CustomerType.B2C);
        s.setPaymentMode(PaymentMode.PREPAID);
        s.setDeliveryType(DeliveryType.INTERCITY);
        s.setPickupType(PickupType.DA_PICKUP);
        s.setDropType(DropType.DA_DELIVERY);
        s.setOriginCity("DEL");
        s.setOriginAddress(addr("A", 28.5, 77.1));
        s.setDestCity("BOM");
        s.setDestAddress(addr("B", 19.0, 72.8));
        s.setChargeableWeightGrams(1500);
        s.setSlaCommitmentMinutes((short) 1440);
        UUID orderId = UUID.randomUUID();
        s.setOrderId(orderId);
        ParcelOrder order = new ParcelOrder();
        ReflectionTestUtils.setField(order, "orderRef", "ORD-9");
        when(parcelOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        producer.onShipmentBooked(new ShipmentBooked(s));

        ShipmentCreatedEvent ev = capturePublished(ShipmentCreatedEvent.class);
        assertThat(ev.getEventType()).isEqualTo(ShipmentEventType.CREATED);
        assertThat(ev.getOriginLat()).isEqualTo(28.5);
        assertThat(ev.getSlaCommitmentMinutes()).isEqualTo(1440);
        assertThat(ev.getOrderRef()).isEqualTo("ORD-9");
    }

    @Test
    void booked_nullAddressesAndSlaAndOrder_handlesNulls() {
        Shipment s = new Shipment();
        s.setShipmentRef("REF"); // addresses null, sla null, orderId null
        producer.onShipmentBooked(new ShipmentBooked(s));
        ShipmentCreatedEvent ev = capturePublished(ShipmentCreatedEvent.class);
        assertThat(ev.getOriginLat()).isNull();
        assertThat(ev.getSlaCommitmentMinutes()).isNull();
        assertThat(ev.getOrderRef()).isNull();
    }

    @Test
    void cancelled_mapsFields_bothOccurredAtBranches() {
        producer.onShipmentCancelled(new ShipmentCancelled(UUID.randomUUID(), "REF",
                ShipmentState.BOOKED, "changed mind", true, 4720L, Instant.now()));
        ShipmentCancelledEvent ev = capturePublished(ShipmentCancelledEvent.class);
        assertThat(ev.getEventType()).isEqualTo(ShipmentEventType.CANCELLED);
        assertThat(ev.getRefundInitiated()).isTrue();

        producer.onShipmentCancelled(new ShipmentCancelled(UUID.randomUUID(), "REF2",
                ShipmentState.BOOKED, "x", false, null, null)); // null occurredAt → now
        verify(eventPublisher, org.mockito.Mockito.times(2))
                .publish(eq(EventStreams.SHIPMENTS_EVENTS), org.mockito.ArgumentMatchers.any());
    }

    private <T> T capturePublished(Class<T> type) {
        ArgumentCaptor<com.oneday.common.kafka.DomainEvent> cap =
                ArgumentCaptor.forClass(com.oneday.common.kafka.DomainEvent.class);
        verify(eventPublisher).publish(eq(EventStreams.SHIPMENTS_EVENTS), cap.capture());
        return type.cast(cap.getValue());
    }
}
