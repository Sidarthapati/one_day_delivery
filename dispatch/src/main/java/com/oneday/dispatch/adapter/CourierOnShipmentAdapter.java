package com.oneday.dispatch.adapter;

import com.oneday.common.port.CourierOnShipmentPort;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.common.port.DaDirectoryPort.DaContact;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * M5-side implementation of {@link CourierOnShipmentPort}: resolves the shipment to its currently
 * assigned DA ({@code dispatch_queue}, the same lookup as {@link LiveDaPositionAdapter}) and reads that
 * DA's field contact from the auth DA directory. Empty when no DA is on the parcel — so the customer
 * sees the courier in exactly the pickup/last-mile states the live DA dot appears.
 */
@Component
class CourierOnShipmentAdapter implements CourierOnShipmentPort {

    private final DispatchQueueRepository queueRepository;
    private final DaDirectoryPort daDirectoryPort;

    CourierOnShipmentAdapter(DispatchQueueRepository queueRepository, DaDirectoryPort daDirectoryPort) {
        this.queueRepository = queueRepository;
        this.daDirectoryPort = daDirectoryPort;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Courier> forShipment(UUID shipmentId) {
        List<DispatchQueue> active = queueRepository.findActiveByShipmentId(shipmentId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        DispatchQueue task = active.get(0);   // newest active assignment — the DA carrying the parcel now
        Map<UUID, DaContact> contacts = daDirectoryPort.contactsFor(List.of(task.getDaId()));
        DaContact c = contacts.get(task.getDaId());
        if (c == null) {
            return Optional.empty();
        }
        Role role = task.getTaskType() == TaskType.PICKUP ? Role.PICKUP : Role.DELIVERY;
        return Optional.of(new Courier(c.name(), c.phone(), role));
    }
}
