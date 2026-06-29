package com.oneday.dispatch.demo;

import com.oneday.common.port.DaPickupQueuePort;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * M5-side implementation of {@link DaPickupQueuePort}: exposes the active pickup queue so M6's demo run
 * can collect the real parcels M5 dispatched. Demo-only ({@code @Profile("!prod")}) — the bridge exists
 * to make the on-map handoff reflect M5's assignment decisions, not to expose dispatch internals broadly.
 */
@Component
@Profile("!prod")
class DaPickupQueuePortAdapter implements DaPickupQueuePort {

    private static final Set<TaskStatus> ACTIVE = Set.of(TaskStatus.QUEUED, TaskStatus.IN_PROGRESS);

    private final DispatchQueueRepository queueRepository;

    DaPickupQueuePortAdapter(DispatchQueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @Override
    public List<QueuedPickup> queuedPickups(UUID cityId, LocalDate date) {
        return byStatus(cityId, date, TaskType.PICKUP, ACTIVE);
    }

    @Override
    public List<QueuedPickup> pickedUpPickups(UUID cityId, LocalDate date) {
        // IN_PROGRESS = the DA has collected it (OTP-verified at the door) and is carrying it to the van.
        return byStatus(cityId, date, TaskType.PICKUP, Set.of(TaskStatus.IN_PROGRESS));
    }

    @Override
    public List<QueuedPickup> queuedDeliveries(UUID cityId, LocalDate date) {
        return byStatus(cityId, date, TaskType.DELIVERY, ACTIVE);
    }

    private List<QueuedPickup> byStatus(UUID cityId, LocalDate date, TaskType type, Set<TaskStatus> statuses) {
        return queueRepository.findByCityIdAndOperatingDate(cityId, date).stream()
                .filter(q -> q.getTaskType() == type && statuses.contains(q.getStatus()))
                .map(q -> new QueuedPickup(q.getDaId(), q.getShipmentId(), q.getTileId()))
                .toList();
    }
}
