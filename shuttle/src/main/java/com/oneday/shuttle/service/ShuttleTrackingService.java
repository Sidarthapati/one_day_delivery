package com.oneday.shuttle.service;

import com.oneday.shuttle.domain.ShuttleLiveStatus;
import com.oneday.shuttle.repository.ShuttleLiveStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Overwrites the single {@code shuttle_live_status} row for an agent on every GPS ping — no Kafka, no
 * history. Mirror of the van's in-process telemetry handling, minus stops/lateness.
 */
@Service
public class ShuttleTrackingService {

    private final ShuttleLiveStatusRepository repository;
    private final Clock clock;

    public ShuttleTrackingService(ShuttleLiveStatusRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void ping(UUID agentId, String cityId, double lat, double lon) {
        ShuttleLiveStatus status = repository.findById(agentId)
                .orElseGet(() -> ShuttleLiveStatus.builder().agentId(agentId).build());
        status.setCityId(cityId);
        status.setLastLat(lat);
        status.setLastLon(lon);
        status.setLastSeenAt(clock.instant());
        repository.save(status);
    }
}
