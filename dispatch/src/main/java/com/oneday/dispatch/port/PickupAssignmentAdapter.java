package com.oneday.dispatch.port;

import com.oneday.common.port.PickupAssignmentPort;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Dispatch-side implementation of {@link PickupAssignmentPort}: the active pickup task for a shipment
 * (the one that survives the partial-unique index, FAILED/CANCELLED excluded) names the DA M5 assigned.
 */
@Component
class PickupAssignmentAdapter implements PickupAssignmentPort {

    private final DispatchQueueRepository queueRepository;

    PickupAssignmentAdapter(DispatchQueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @Override
    public boolean isActivePickupDa(UUID daId, UUID shipmentId) {
        if (daId == null || shipmentId == null) {
            return false;
        }
        return queueRepository.findActiveByShipmentIdAndTaskType(shipmentId, TaskType.PICKUP)
                .map(q -> daId.equals(q.getDaId()))
                .orElse(false);
    }
}
