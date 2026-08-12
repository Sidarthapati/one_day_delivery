package com.oneday.shuttle.adapter;

import com.oneday.common.port.LivePosition;
import com.oneday.common.port.LiveShuttlePositionPort;
import com.oneday.shuttle.domain.ShuttleLeg;
import com.oneday.shuttle.repository.ShuttleLegRepository;
import com.oneday.shuttle.repository.ShuttleLiveStatusRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * M12-side implementation of {@link LiveShuttlePositionPort}: a parcel's most recent {@code shuttle_leg}
 * names the carrying agent, and that agent's {@code shuttle_live_status} row is the live GPS. Empty when
 * the parcel was never on a shuttle or the agent has no fix — the tracking read path then draws the
 * static airport pin. Freshness is judged by the reader (via {@code lastSeenAt}), same as the van port.
 */
@Component
class LiveShuttlePositionAdapter implements LiveShuttlePositionPort {

    private final ShuttleLegRepository legRepository;
    private final ShuttleLiveStatusRepository liveStatusRepository;

    LiveShuttlePositionAdapter(ShuttleLegRepository legRepository,
                               ShuttleLiveStatusRepository liveStatusRepository) {
        this.legRepository = legRepository;
        this.liveStatusRepository = liveStatusRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LivePosition> forShipment(UUID shipmentId) {
        // parcelId == shipment UUID in v1.
        return legRepository.findFirstByParcelIdOrderByCreatedAtDesc(shipmentId)
                .map(ShuttleLeg::getAgentId)
                .flatMap(liveStatusRepository::findById)
                .filter(s -> s.getLastLat() != null && s.getLastLon() != null)
                .map(s -> new LivePosition(
                        s.getLastLat(), s.getLastLon(), s.getLastSeenAt(), null, s.getAgentId()));
    }
}
