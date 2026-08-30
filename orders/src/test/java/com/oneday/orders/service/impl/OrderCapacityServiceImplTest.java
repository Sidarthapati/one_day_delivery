package com.oneday.orders.service.impl;

import com.oneday.common.port.DaPickupLoadPort;
import com.oneday.common.port.DaPickupLoadPort.AssignedPickupLoad;
import com.oneday.orders.config.OrderCapacityProperties;
import com.oneday.orders.domain.ParcelOrder;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.OrderCapacityService.DaCapacityExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The capacity gate only bites when a DA is already on the order's pickup, and only rejects when the
 * summed vehicle load would exceed the configured capacity. When dispatch is unwired or no DA is
 * assigned, it must stay out of the way (add-to-order still works pre-dispatch).
 */
@SuppressWarnings("unchecked")
class OrderCapacityServiceImplTest {

    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final OrderCapacityProperties properties = new OrderCapacityProperties();
    private final ObjectProvider<DaPickupLoadPort> provider = mock(ObjectProvider.class);
    private final DaPickupLoadPort port = mock(DaPickupLoadPort.class);

    private final OrderCapacityServiceImpl service =
            new OrderCapacityServiceImpl(shipmentRepository, properties, provider);

    private static final UUID ORDER_ID = UUID.randomUUID();

    private static ParcelOrder order() {
        ParcelOrder o = mock(ParcelOrder.class);
        when(o.getId()).thenReturn(ORDER_ID);
        when(o.getCityId()).thenReturn("DELHI");
        when(o.getOrderRef()).thenReturn("1DD-ORD-DEL-20260830-00001");
        return o;
    }

    // Standalone factory — never call this inside another when(...).thenReturn(...) argument.
    private static Shipment ship(UUID id, int chargeableGrams) {
        Shipment s = mock(Shipment.class);
        when(s.getId()).thenReturn(id);
        when(s.getChargeableWeightGrams()).thenReturn(chargeableGrams);
        return s;
    }

    @Test
    void portUnavailable_skipsSilently() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> service.ensureCapacityForAdd(order(), 5_000)).doesNotThrowAnyException();
        verifyNoInteractions(shipmentRepository);
    }

    @Test
    void noDaAssigned_skips() {
        when(provider.getIfAvailable()).thenReturn(port);
        UUID sibId = UUID.randomUUID();
        Shipment sib = ship(sibId, 10_000);
        when(shipmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID)).thenReturn(List.of(sib));
        when(port.assignedPickupLoad(sibId)).thenReturn(Optional.empty());

        assertThatCode(() -> service.ensureCapacityForAdd(order(), 40_000)).doesNotThrowAnyException();
    }

    @Test
    void underCapacity_ok() {
        properties.setDaVehicleGrams(50_000);   // pin the cap so the test is independent of the default
        wireAssignedDa(40_000);   // 40kg already on the vehicle

        assertThatCode(() -> service.ensureCapacityForAdd(order(), 5_000))   // +5kg = 45kg <= 50kg
                .doesNotThrowAnyException();
    }

    @Test
    void overCapacity_rejects() {
        properties.setDaVehicleGrams(50_000);   // pin the cap so the test is independent of the default
        wireAssignedDa(40_000);   // 40kg already on the vehicle

        assertThatThrownBy(() -> service.ensureCapacityForAdd(order(), 15_000))   // +15kg = 55kg > 50kg
                .isInstanceOf(DaCapacityExceededException.class);
    }

    @Test
    void perCityOverrideApplies() {
        properties.getDaVehicleGramsByCity().put("DELHI", 30_000);   // tighter cap for Delhi
        wireAssignedDa(20_000);

        assertThatThrownBy(() -> service.ensureCapacityForAdd(order(), 15_000))   // 35kg > 30kg
                .isInstanceOf(DaCapacityExceededException.class);
    }

    /** DA assigned, carrying {@code onVehicleGrams} across two queued parcels. */
    private void wireAssignedDa(int onVehicleGrams) {
        when(provider.getIfAvailable()).thenReturn(port);

        UUID sibId = UUID.randomUUID();
        Shipment sib = ship(sibId, onVehicleGrams / 2);
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        Shipment sh1 = ship(s1, onVehicleGrams / 2);
        Shipment sh2 = ship(s2, onVehicleGrams / 2);

        when(shipmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID)).thenReturn(List.of(sib));
        when(port.assignedPickupLoad(sibId))
                .thenReturn(Optional.of(new AssignedPickupLoad(UUID.randomUUID(), List.of(s1, s2))));
        when(shipmentRepository.findAllById(any())).thenReturn(List.of(sh1, sh2));
    }
}
