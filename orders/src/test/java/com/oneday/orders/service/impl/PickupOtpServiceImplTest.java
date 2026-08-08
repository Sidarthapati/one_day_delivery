package com.oneday.orders.service.impl;

import com.oneday.orders.config.PickupOtpProperties;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.PickupOtpRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.DevOtpRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PickupOtpServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void generate_populatesDevOtpRegistryWithCleartext() {
        PickupOtpRepository otpRepo = mock(PickupOtpRepository.class);
        ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
        PickupOtpProperties props = new PickupOtpProperties();
        DevOtpRegistry registry = new DevOtpRegistry();
        ObjectProvider<DevOtpRegistry> provider = mock(ObjectProvider.class);
        // Mirror ObjectProvider.ifAvailable: invoke the consumer with the (present) registry.
        doAnswer(inv -> { ((Consumer<DevOtpRegistry>) inv.getArgument(0)).accept(registry); return null; })
                .when(provider).ifAvailable(any());

        UUID shipmentId = UUID.randomUUID();
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(new Shipment()));

        PickupOtpServiceImpl service = new PickupOtpServiceImpl(otpRepo, shipmentRepo, props, provider);
        String otp = service.generate(shipmentId);

        // The peek must echo exactly the cleartext the DA is given.
        assertThat(registry.get(shipmentId)).isEqualTo(otp);
        assertThat(otp).hasSize(4);
    }
}
