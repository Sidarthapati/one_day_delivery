package com.oneday.hub.service.impl;

import com.oneday.hub.config.ClockConfig;
import com.oneday.hub.config.HubProperties;
import com.oneday.hub.domain.DeliveryBagStatus;
import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.domain.HubLoadSnapshot;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.DeliveryBagRepository;
import com.oneday.hub.repository.FlightBagRepository;
import com.oneday.hub.repository.HubLoadSnapshotRepository;
import com.oneday.hub.repository.InboundReceiptRepository;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.HubLoadService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Computes the rolling load snapshot and back-pressures on overload (§11). hub_id == city_id in v1. */
@Service
class HubLoadServiceImpl implements HubLoadService {

    private final StandRepository standRepository;
    private final FlightBagRepository flightBagRepository;
    private final DeliveryBagRepository deliveryBagRepository;
    private final InboundReceiptRepository inboundReceiptRepository;
    private final HubLoadSnapshotRepository snapshotRepository;
    private final HubEventProducer eventProducer;
    private final HubProperties properties;
    private final Clock clock;

    HubLoadServiceImpl(StandRepository standRepository,
                       FlightBagRepository flightBagRepository,
                       DeliveryBagRepository deliveryBagRepository,
                       InboundReceiptRepository inboundReceiptRepository,
                       HubLoadSnapshotRepository snapshotRepository,
                       HubEventProducer eventProducer,
                       HubProperties properties,
                       Clock clock) {
        this.standRepository = standRepository;
        this.flightBagRepository = flightBagRepository;
        this.deliveryBagRepository = deliveryBagRepository;
        this.inboundReceiptRepository = inboundReceiptRepository;
        this.snapshotRepository = snapshotRepository;
        this.eventProducer = eventProducer;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public HubLoadSnapshot snapshot(UUID hubId) {
        Instant now = clock.instant();
        String waveKey = now.atZone(ClockConfig.IST).toLocalDate().toString();   // one wave per IST day in v1

        long totalStands = standRepository.countByHubId(hubId);
        long occupiedStands = flightBagRepository.countByHubIdAndStatus(hubId, FlightBagStatus.OPEN)
                + deliveryBagRepository.countByHubIdAndStatus(hubId, DeliveryBagStatus.OPEN);
        int occupancyPct = totalStands > 0 ? (int) Math.round(occupiedStands * 100.0 / totalStands) : 0;

        Instant windowStart = now.atZone(ClockConfig.IST).toLocalDate().atStartOfDay(ClockConfig.IST).toInstant();
        long inboundCount = inboundReceiptRepository.countByHubIdAndReceivedAtAfter(hubId, windowStart);
        long bagged = flightBagRepository.sumOpenParcelCount(hubId) + deliveryBagRepository.sumOpenParcelCount(hubId);
        int awaitingSort = (int) Math.max(0, inboundCount - bagged);   // arrivals not yet in an open bag (proxy)

        boolean overloaded = occupancyPct >= properties.getStandHighWaterPct();

        HubLoadSnapshot snapshot = snapshotRepository.save(HubLoadSnapshot.builder()
                .cityId(hubId)   // hub_id == city_id in v1
                .hubId(hubId)
                .waveKey(waveKey)
                .inboundCount((int) inboundCount)
                .awaitingSort(awaitingSort)
                .standOccupancyPct(occupancyPct)
                .projectedClearAt(null)   // throughput-model projection deferred (Q7)
                .overloaded(overloaded)
                .snapshotAt(now)
                .build());

        if (overloaded) {
            // Flag + alert (M10 + station mgr + advisory M4 throttle). Escalate, never discard.
            eventProducer.emitHubOverloadAlert(snapshot);
        }
        return snapshot;
    }
}
