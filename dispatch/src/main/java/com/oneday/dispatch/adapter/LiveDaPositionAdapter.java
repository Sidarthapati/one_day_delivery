package com.oneday.dispatch.adapter;

import com.oneday.common.port.LiveDaPositionPort;
import com.oneday.common.port.LivePosition;
import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * M5-side implementation of {@link LiveDaPositionPort}: resolves the shipment to its currently
 * assigned DA ({@code dispatch_queue}) and reads that DA's last GPS fix from {@code da_status}.
 * Empty when no DA is actively carrying the parcel or no GPS has been reported yet — the tracker
 * then falls back to a static node.
 */
@Component
class LiveDaPositionAdapter implements LiveDaPositionPort {

    private final DispatchQueueRepository queueRepository;
    private final DaStatusRepository daStatusRepository;

    LiveDaPositionAdapter(DispatchQueueRepository queueRepository, DaStatusRepository daStatusRepository) {
        this.queueRepository = queueRepository;
        this.daStatusRepository = daStatusRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LivePosition> forShipment(UUID shipmentId) {
        List<DispatchQueue> active = queueRepository.findActiveByShipmentId(shipmentId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        UUID daId = active.get(0).getDaId();
        return daStatusRepository.findByDaId(daId)
                .filter(LiveDaPositionAdapter::hasFix)
                .map(s -> new LivePosition(
                        s.getLastGpsLat(), s.getLastGpsLon(), s.getLastHeartbeat(), null, daId));
    }

    private static boolean hasFix(DaStatus s) {
        return s.getLastGpsLat() != null && s.getLastGpsLon() != null;
    }
}
