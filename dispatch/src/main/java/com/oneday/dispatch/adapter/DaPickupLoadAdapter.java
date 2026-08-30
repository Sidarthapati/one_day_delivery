package com.oneday.dispatch.adapter;

import com.oneday.common.port.DaPickupLoadPort;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * M5-side implementation of {@link DaPickupLoadPort}: resolves the shipment to its currently assigned
 * pickup DA ({@code dispatch_queue}, same lookup as {@link CourierOnShipmentAdapter}) and returns every
 * shipment on that DA's active pickup queue for the day. Orders sums those parcels' weights against the
 * DA's vehicle capacity before adding one more. Empty when no DA is on the parcel's pickup yet.
 */
@Component
class DaPickupLoadAdapter implements DaPickupLoadPort {

    private static final List<TaskStatus> ACTIVE = List.of(TaskStatus.QUEUED, TaskStatus.IN_PROGRESS);

    private final DispatchQueueRepository queueRepository;

    DaPickupLoadAdapter(DispatchQueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssignedPickupLoad> assignedPickupLoad(UUID shipmentId) {
        // Find the DA on this shipment's active PICKUP task (newest assignment first).
        DispatchQueue pickup = queueRepository.findActiveByShipmentId(shipmentId).stream()
                .filter(t -> t.getTaskType() == TaskType.PICKUP)
                .findFirst()
                .orElse(null);
        if (pickup == null) {
            return Optional.empty();
        }

        // Everything on that DA's active pickup queue for the day = the parcels already committed to
        // the vehicle (queued to collect + already collected, not yet handed to the van).
        List<UUID> pickupShipmentIds = queueRepository
                .findByDaIdAndOperatingDateAndStatusIn(pickup.getDaId(), pickup.getOperatingDate(), ACTIVE)
                .stream()
                .filter(t -> t.getTaskType() == TaskType.PICKUP)
                .map(DispatchQueue::getShipmentId)
                .distinct()
                .toList();

        return Optional.of(new AssignedPickupLoad(pickup.getDaId(), pickupShipmentIds));
    }
}
