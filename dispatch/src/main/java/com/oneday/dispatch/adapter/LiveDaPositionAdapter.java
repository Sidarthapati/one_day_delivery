package com.oneday.dispatch.adapter;

import com.oneday.common.port.LiveDaPositionPort;
import com.oneday.common.port.LivePosition;
import com.oneday.dispatch.domain.DaGpsPing;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * M5-side implementation of {@link LiveDaPositionPort}: resolves the shipment to its currently
 * assigned DA ({@code dispatch_queue}) and reads that DA's most recent {@code da_gps_ping}. The ping
 * trail is written on every heartbeat (unconditionally), unlike the {@code da_status} snapshot which
 * only updates while the DA is loaded in the in-memory roster — so this stays live even for a DA that
 * fell out of memory (restart/eviction). Empty when no DA is carrying the parcel or no GPS yet.
 */
@Component
class LiveDaPositionAdapter implements LiveDaPositionPort {

    private final DispatchQueueRepository queueRepository;
    private final DaGpsPingRepository gpsPingRepository;

    LiveDaPositionAdapter(DispatchQueueRepository queueRepository, DaGpsPingRepository gpsPingRepository) {
        this.queueRepository = queueRepository;
        this.gpsPingRepository = gpsPingRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LivePosition> forShipment(UUID shipmentId) {
        List<DispatchQueue> active = queueRepository.findActiveByShipmentId(shipmentId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        UUID daId = active.get(0).getDaId();
        DaGpsPing fix = gpsPingRepository.findTopByDaIdOrderByRecordedAtDesc(daId);
        return fix == null
                ? Optional.empty()
                : Optional.of(new LivePosition(fix.getLat(), fix.getLon(), fix.getRecordedAt(), null, daId));
    }
}
