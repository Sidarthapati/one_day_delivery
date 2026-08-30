package com.oneday.orders.service.impl;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.events.ReceiverRejectedEvent;
import com.oneday.common.port.NotificationPort;
import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.common.port.dto.NotificationRequest;
import com.oneday.orders.config.OrdersDeliveryProperties;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.DeliveryConfirmation;
import com.oneday.orders.domain.DeliveryConfirmationStatus;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.DeliveryConfirmationRepository;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the receiver accept/reject confirmation service (repos + notify + bus mocked). */
class DeliveryConfirmationServiceImplTest {

    private ShipmentRepository shipmentRepo;
    private ParcelOrderRepository orderRepo;
    private DeliveryConfirmationRepository confirmationRepo;
    private NotificationPort notificationPort;
    private EventPublisher eventPublisher;
    private DeliveryConfirmationServiceImpl service;

    private final UUID shipmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        shipmentRepo = mock(ShipmentRepository.class);
        orderRepo = mock(ParcelOrderRepository.class);
        confirmationRepo = mock(DeliveryConfirmationRepository.class);
        notificationPort = mock(NotificationPort.class);
        eventPublisher = mock(EventPublisher.class);
        OrdersDeliveryProperties props = new OrdersDeliveryProperties();
        props.setCustomerLandingBaseUrl("http://localhost:3000");
        service = new DeliveryConfirmationServiceImpl(shipmentRepo, orderRepo, confirmationRepo,
                notificationPort, eventPublisher, props);
    }

    private Shipment shipmentWithEmail(String email) {
        Address dest = new Address();
        dest.setLatitude(12.97);
        dest.setLongitude(77.61);
        Shipment s = new Shipment();
        s.setReceiverEmail(email);
        s.setReceiverName("Asha");
        s.setShipmentRef("1DD-BLR-20260828-00001");
        s.setDestAddress(dest);
        s.setDestTileId(UUID.randomUUID());
        s.setOrderId(null);
        return s;
    }

    @Test
    void promptCreatesPendingConfirmationAndEmails() {
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(shipmentWithEmail("asha@example.com")));
        when(confirmationRepo.findFirstByShipmentIdAndStatus(shipmentId, DeliveryConfirmationStatus.PENDING))
                .thenReturn(Optional.empty());

        service.promptOnDeparture(shipmentId);

        ArgumentCaptor<DeliveryConfirmation> saved = ArgumentCaptor.forClass(DeliveryConfirmation.class);
        verify(confirmationRepo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DeliveryConfirmationStatus.PENDING);
        assertThat(saved.getValue().getTokenHash()).hasSize(64);   // SHA-256 hex

        ArgumentCaptor<NotificationRequest> req = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationPort).send(req.capture());
        assertThat(req.getValue().type()).isEqualTo(NotificationEventType.RECEIVER_CONFIRM);
        assertThat(req.getValue().recipientEmail()).isEqualTo("asha@example.com");
        assertThat(req.getValue().params().get("link")).contains("/d/");
    }

    @Test
    void promptIsIdempotentWhenLivePromptExists() {
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(shipmentWithEmail("asha@example.com")));
        when(confirmationRepo.findFirstByShipmentIdAndStatus(shipmentId, DeliveryConfirmationStatus.PENDING))
                .thenReturn(Optional.of(new DeliveryConfirmation()));

        service.promptOnDeparture(shipmentId);

        verify(confirmationRepo, never()).save(any());
        verify(notificationPort, never()).send(any());
    }

    @Test
    void promptSkipsWhenNoReceiverEmail() {
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(shipmentWithEmail(null)));
        service.promptOnDeparture(shipmentId);
        verify(confirmationRepo, never()).save(any());
        verify(notificationPort, never()).send(any());
    }

    @Test
    void rejectMarksRejectedAndPublishesEvent() {
        DeliveryConfirmation c = pending();
        when(confirmationRepo.findByTokenHash(anyString())).thenReturn(Optional.of(c));
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(shipmentWithEmail("asha@example.com")));

        service.reject("tok", "shift_2");

        assertThat(c.getStatus()).isEqualTo(DeliveryConfirmationStatus.REJECTED);
        assertThat(c.getResponseShift()).isEqualTo("SHIFT_2");
        ArgumentCaptor<ReceiverRejectedEvent> ev = ArgumentCaptor.forClass(ReceiverRejectedEvent.class);
        verify(eventPublisher).publish(eq(EventStreams.DELIVERY_CONFIRMATIONS), ev.capture());
        assertThat(ev.getValue().shipmentId()).isEqualTo(shipmentId);
        assertThat(ev.getValue().targetShift()).isEqualTo("SHIFT_2");
    }

    @Test
    void rejectRejectsAnInvalidShift() {
        assertThatThrownBy(() -> service.reject("tok", "SHIFT_9"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void acceptMarksAccepted() {
        DeliveryConfirmation c = pending();
        when(confirmationRepo.findByTokenHash(anyString())).thenReturn(Optional.of(c));
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(shipmentWithEmail("asha@example.com")));

        service.accept("tok");

        assertThat(c.getStatus()).isEqualTo(DeliveryConfirmationStatus.ACCEPTED);
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    @Test
    void getByUnknownTokenIs404() {
        when(confirmationRepo.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getByToken("nope"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void expireStaleFlipsPendingToExpired() {
        DeliveryConfirmation c = pending();
        when(confirmationRepo.findByStatusAndExpiresAtBefore(eq(DeliveryConfirmationStatus.PENDING), any()))
                .thenReturn(List.of(c));

        assertThat(service.expireStale()).isEqualTo(1);
        assertThat(c.getStatus()).isEqualTo(DeliveryConfirmationStatus.EXPIRED);
    }

    private DeliveryConfirmation pending() {
        DeliveryConfirmation c = new DeliveryConfirmation();
        c.setShipmentId(shipmentId);
        c.setStatus(DeliveryConfirmationStatus.PENDING);
        c.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        return c;
    }
}
