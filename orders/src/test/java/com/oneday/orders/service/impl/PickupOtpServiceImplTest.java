package com.oneday.orders.service.impl;

import com.oneday.common.port.NotificationPort;
import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.common.port.dto.NotificationRequest;
import com.oneday.orders.config.PickupOtpProperties;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.PickupOtpRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.DevOtpRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PickupOtpServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void generate_populatesDevRegistryAndSmsesTheOtpToTheSender() {
        PickupOtpRepository otpRepo = mock(PickupOtpRepository.class);
        ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
        PickupOtpProperties props = new PickupOtpProperties();
        NotificationPort notificationPort = mock(NotificationPort.class);
        DevOtpRegistry registry = new DevOtpRegistry();
        ObjectProvider<DevOtpRegistry> provider = mock(ObjectProvider.class);
        // Mirror ObjectProvider.ifAvailable: invoke the consumer with the (present) registry.
        doAnswer(inv -> { ((Consumer<DevOtpRegistry>) inv.getArgument(0)).accept(registry); return null; })
                .when(provider).ifAvailable(any());

        UUID shipmentId = UUID.randomUUID();
        Shipment shipment = new Shipment();
        shipment.setSenderPhone("+919000000001");
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(shipment));

        PickupOtpServiceImpl service =
                new PickupOtpServiceImpl(otpRepo, shipmentRepo, props, notificationPort, provider);
        String otp = service.generate(shipmentId);

        // The peek must echo exactly the cleartext the DA is given.
        assertThat(registry.get(shipmentId)).isEqualTo(otp);
        assertThat(otp).hasSize(4);

        // …and the same cleartext is SMSed to the sender via the notification seam.
        ArgumentCaptor<NotificationRequest> req = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationPort).send(req.capture());
        assertThat(req.getValue().type()).isEqualTo(NotificationEventType.OTP_GENERATED);
        assertThat(req.getValue().recipientPhone()).isEqualTo("+919000000001");
        assertThat(req.getValue().params().get("otp")).isEqualTo(otp);
    }
}
