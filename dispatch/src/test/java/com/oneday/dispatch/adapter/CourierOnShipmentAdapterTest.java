package com.oneday.dispatch.adapter;

import com.oneday.common.port.CourierOnShipmentPort.Courier;
import com.oneday.common.port.CourierOnShipmentPort.Role;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.common.port.DaDirectoryPort.DaContact;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourierOnShipmentAdapterTest {

    @Mock DispatchQueueRepository queueRepository;
    @Mock DaDirectoryPort daDirectoryPort;

    private final UUID shipmentId = UUID.randomUUID();
    private final UUID daId = UUID.randomUUID();

    private CourierOnShipmentAdapter adapter() {
        return new CourierOnShipmentAdapter(queueRepository, daDirectoryPort);
    }

    private DispatchQueue task(TaskType type) {
        DispatchQueue q = new DispatchQueue();
        q.setDaId(daId);
        q.setShipmentId(shipmentId);
        q.setTaskType(type);
        return q;
    }

    @Test
    void activePickupTask_returnsTheDaContactAndPickupRole() {
        when(queueRepository.findActiveByShipmentId(shipmentId)).thenReturn(List.of(task(TaskType.PICKUP)));
        when(daDirectoryPort.contactsFor(List.of(daId)))
                .thenReturn(Map.of(daId, new DaContact("Ravi Kumar", "+919876543210")));

        Optional<Courier> courier = adapter().forShipment(shipmentId);

        assertThat(courier).contains(new Courier("Ravi Kumar", "+919876543210", Role.PICKUP));
    }

    @Test
    void deliveryTask_carriesTheDeliveryRole() {
        when(queueRepository.findActiveByShipmentId(shipmentId)).thenReturn(List.of(task(TaskType.DELIVERY)));
        when(daDirectoryPort.contactsFor(List.of(daId)))
                .thenReturn(Map.of(daId, new DaContact("Asha S", "+919000000000")));

        assertThat(adapter().forShipment(shipmentId)).map(Courier::role).contains(Role.DELIVERY);
    }

    @Test
    void noActiveTask_isEmpty() {
        when(queueRepository.findActiveByShipmentId(shipmentId)).thenReturn(List.of());

        assertThat(adapter().forShipment(shipmentId)).isEmpty();
    }

    @Test
    void activeTaskButDirectoryHasNoContact_isEmpty() {
        when(queueRepository.findActiveByShipmentId(shipmentId)).thenReturn(List.of(task(TaskType.PICKUP)));
        when(daDirectoryPort.contactsFor(List.of(daId))).thenReturn(Map.of());

        assertThat(adapter().forShipment(shipmentId)).isEmpty();
    }
}
