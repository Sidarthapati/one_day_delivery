package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.port.NotificationPort;
import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.common.port.dto.NotificationRequest;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.ReviseEtaResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentEtaServiceImplTest {

    @Mock private ShipmentRepository shipments;
    @Mock private B2bAccountRepository accounts;
    @Mock private NotificationPort notificationPort;

    private static final long GRACE = 15;

    private ShipmentEtaServiceImpl service() {
        return new ShipmentEtaServiceImpl(shipments, accounts, notificationPort, GRACE);
    }

    private Shipment b2cShipment(Instant promised) {
        Shipment s = new Shipment();
        s.setShipmentRef("1DD-DEL-1");
        s.setCustomerType(CustomerType.B2C);
        s.setSenderEmail("sender@home.example");
        s.setSenderPhone("+919000000009");
        s.setEtaPromised(promised);
        when(shipments.findByShipmentRef("1DD-DEL-1")).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void slipBeyondGrace_notifiesTheSender_andRecordsNewEta() {
        Instant promised = Instant.parse("2026-08-27T10:00:00Z");
        Shipment s = b2cShipment(promised);
        Instant newEta = promised.plus(Duration.ofHours(3)); // clearly late

        ReviseEtaResponse r = service().reviseEta("1DD-DEL-1", newEta, "flight delayed", "ops-1", null);

        assertThat(r.delayed()).isTrue();
        assertThat(r.customerNotified()).isTrue();
        assertThat(r.etaUpdated()).isEqualTo(newEta);
        assertThat(s.getEtaUpdated()).isEqualTo(newEta); // written on the entity

        ArgumentCaptor<NotificationRequest> req = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationPort).send(req.capture());
        assertThat(req.getValue().type()).isEqualTo(NotificationEventType.SHIPMENT_DELAYED);
        assertThat(req.getValue().recipientEmail()).isEqualTo("sender@home.example");
        assertThat(req.getValue().accountId()).isNull(); // retail → no account
        assertThat(req.getValue().params().get("shipment_ref")).isEqualTo("1DD-DEL-1");
        assertThat(req.getValue().params()).containsKeys("new_eta", "original_eta");
    }

    @Test
    void earlierOrWithinGrace_isNotADelay_andDoesNotNotify() {
        Instant promised = Instant.parse("2026-08-27T10:00:00Z");
        b2cShipment(promised);
        Instant newEta = promised.plus(Duration.ofMinutes(5)); // within the 15-min grace

        ReviseEtaResponse r = service().reviseEta("1DD-DEL-1", newEta, null, "ops-1", null);

        assertThat(r.delayed()).isFalse();
        assertThat(r.customerNotified()).isFalse();
        verify(notificationPort, never()).send(any());
    }

    @Test
    void noPromisedEta_cannotBeADelay() {
        b2cShipment(null); // never got an ETA at booking
        ReviseEtaResponse r = service().reviseEta("1DD-DEL-1", Instant.parse("2026-08-27T20:00:00Z"), null, "ops-1", null);
        assertThat(r.delayed()).isFalse();
        verify(notificationPort, never()).send(any());
    }

    @Test
    void stationManagerCannotReviseAParcelOutsideTheirCity() {
        Shipment s = new Shipment();
        s.setShipmentRef("1DD-DEL-7");
        s.setCustomerType(CustomerType.B2C);
        s.setOriginCity("DEL");
        s.setDestCity("BLR");
        s.setEtaPromised(Instant.parse("2026-08-27T10:00:00Z"));
        when(shipments.findByShipmentRef("1DD-DEL-7")).thenReturn(Optional.of(s));

        // A MAA manager (scope "MAA") touches neither origin nor dest → 404, nothing notified.
        assertThatThrownBy(() -> service().reviseEta("1DD-DEL-7", Instant.parse("2026-08-27T20:00:00Z"), null, "sm-maa", "MAA"))
                .isInstanceOf(ResponseStatusException.class);
        verify(notificationPort, never()).send(any());

        // The destination-city manager (scope "BLR") is in scope → allowed, and it's a delay.
        assertThat(service().reviseEta("1DD-DEL-7", Instant.parse("2026-08-27T20:00:00Z"), null, "sm-blr", "BLR").delayed())
                .isTrue();
    }

    @Test
    void b2bDelay_notifiesTheAccountBillingContact_scopedToTheAccount() {
        Instant promised = Instant.parse("2026-08-27T10:00:00Z");
        UUID accountId = UUID.randomUUID();
        Shipment s = new Shipment();
        s.setShipmentRef("1DD-BLR-9");
        s.setCustomerType(CustomerType.B2B);
        s.setB2bAccountId(accountId);
        s.setSenderEmail("warehouse@acme.example");
        s.setEtaPromised(promised);
        when(shipments.findByShipmentRef("1DD-BLR-9")).thenReturn(Optional.of(s));

        B2bAccount acc = new B2bAccount();
        ReflectionTestUtils.setField(acc, "id", accountId);
        acc.setBillingEmail("billing@acme.example");
        acc.setSupportPhone("+919111111111");
        when(accounts.findById(accountId)).thenReturn(Optional.of(acc));

        ReviseEtaResponse r = service().reviseEta("1DD-BLR-9", promised.plus(Duration.ofHours(4)), null, "ops-1", null);

        assertThat(r.customerNotified()).isTrue();
        ArgumentCaptor<NotificationRequest> req = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationPort).send(req.capture());
        assertThat(req.getValue().recipientEmail()).isEqualTo("billing@acme.example"); // account, not sender
        assertThat(req.getValue().accountId()).isEqualTo(accountId); // shows in the merchant's bell
    }
}
