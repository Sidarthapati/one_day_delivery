package com.oneday.routing.service.impl;

import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.ManifestStatus;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.VanLiveStatus;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.dto.TelemetryAck;
import com.oneday.routing.dto.VanTelemetryRequest;
import com.oneday.routing.events.RouteDeviationProducer;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.repository.VanLiveStatusRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.CustodyService;
import com.oneday.routing.service.VanTrackingService;
import com.oneday.routing.service.model.CustodyResult;
import com.oneday.routing.service.model.VanCustodyCommand;
import com.oneday.common.port.ScanLedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * The run-time brain (§14.2). Every telemetry event first overwrites the van's single live-status row
 * (M6-D-012) — that powers the ops map and costs one upsert. Then, by type: GPS does nothing more;
 * ARRIVED computes lateness vs the locked plan, flips the manifest IN_PROGRESS, and escalates to
 * VAN_RUNNING_LATE past the threshold; DELIVER/COLLECT route to {@link CustodyService}. No raw ping
 * ever touches Kafka.
 */
@Service
class VanTrackingServiceImpl implements VanTrackingService {

    private static final Logger log = LoggerFactory.getLogger(VanTrackingServiceImpl.class);

    private final VanLiveStatusRepository liveRepository;
    private final VanManifestRepository manifestRepository;
    private final RoutePlanRepository planRepository;
    private final RoutePlanStopRepository stopRepository;
    private final CustodyService custodyService;
    private final RouteDeviationProducer deviationProducer;
    private final RoutingProperties properties;
    private final Clock clock;

    VanTrackingServiceImpl(VanLiveStatusRepository liveRepository, VanManifestRepository manifestRepository,
                           RoutePlanRepository planRepository, RoutePlanStopRepository stopRepository,
                           CustodyService custodyService, RouteDeviationProducer deviationProducer,
                           RoutingProperties properties, Clock clock) {
        this.liveRepository = liveRepository;
        this.manifestRepository = manifestRepository;
        this.planRepository = planRepository;
        this.stopRepository = stopRepository;
        this.custodyService = custodyService;
        this.deviationProducer = deviationProducer;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TelemetryAck handle(UUID vanId, VanTelemetryRequest e) {
        Instant ts = e.timeOrNow(clock.instant());
        LocalDate today = LocalDate.now(clock);
        PlanCtx ctx = updateLive(vanId, e, ts, today);

        return switch (e.type()) {
            case GPS, DEPARTED_STOP -> TelemetryAck.ok(e.type());
            case ARRIVED_AT_STOP -> onArrived(vanId, e, ts, today, ctx);
            case DELIVER -> onScan(vanId, e, ts, ScanLedgerPort.VanScanType.VAN_TO_DA);
            case COLLECT -> onScan(vanId, e, ts, ScanLedgerPort.VanScanType.DA_TO_VAN);
        };
    }

    // 1. Always overwrite the live row (M6-D-012). Resolves city/plan once when the row is created.
    private PlanCtx updateLive(UUID vanId, VanTelemetryRequest e, Instant ts, LocalDate today) {
        VanLiveStatus live = liveRepository.findById(vanId).orElse(null);
        PlanCtx ctx = resolvePlanCtx(vanId, e.loopIndex(), today);
        if (live == null) {
            UUID cityId = e.cityId() != null ? e.cityId() : (ctx != null ? ctx.cityId() : null);
            if (cityId == null) {
                log.warn("Telemetry for van {} has no city and no manifest today — live row skipped", vanId);
                return ctx;
            }
            live = VanLiveStatus.builder().vanId(vanId).cityId(cityId)
                    .routePlanId(ctx != null ? ctx.routePlanId() : null).build();
        }
        if (e.lat() != null) live.setLastLat(e.lat());
        if (e.lon() != null) live.setLastLon(e.lon());
        if (e.stopSeq() != null) live.setCurrentStopSeq(e.stopSeq());
        live.setLastSeenAt(ts);
        liveRepository.save(live);
        return ctx;
    }

    // 2. ARRIVED: lateness vs plan, flip manifest IN_PROGRESS, emit VAN_ARRIVED / VAN_RUNNING_LATE.
    private TelemetryAck onArrived(UUID vanId, VanTelemetryRequest e, Instant ts, LocalDate today, PlanCtx ctx) {
        if (ctx == null || e.loopIndex() == null || e.stopSeq() == null) {
            log.warn("ARRIVED for van {} without a resolvable plan/loop/stop — recorded position only", vanId);
            return TelemetryAck.arrived(null);
        }
        startLoopIfNeeded(vanId, e.loopIndex(), today, ts);

        Integer minutesLate = latenessVsPlan(ctx.routePlanId(), vanId, e.loopIndex(), e.stopSeq(), ts);
        if (minutesLate != null) {
            liveRepository.findById(vanId).ifPresent(l -> { l.setMinutesLate(minutesLate); liveRepository.save(l); });
        }
        deviationProducer.emitVanArrived(vanId, ctx.cityId(), ctx.routePlanId(), e.loopIndex(), e.stopSeq(),
                e.hexVertexId(), ts);
        if (minutesLate != null && minutesLate >= properties.getLateThresholdMinutes()) {
            // The slip carries to every remaining stop on this loop, so the escalation covers them too.
            deviationProducer.emitVanRunningLate(vanId, ctx.cityId(), ctx.routePlanId(), e.loopIndex(),
                    e.stopSeq(), minutesLate);
        }
        return TelemetryAck.arrived(minutesLate);
    }

    // 3. DELIVER/COLLECT: hand the physical scan to custody (writes M8 ledger + advances the item).
    private TelemetryAck onScan(UUID vanId, VanTelemetryRequest e, Instant ts, ScanLedgerPort.VanScanType type) {
        if (e.parcelId() == null) {
            return TelemetryAck.scan(e.type(), "MISSING_PARCEL_ID");
        }
        CustodyResult result = custodyService.record(new VanCustodyCommand(
                e.parcelId(), type, vanId, e.driverId(), e.daId(), ts));
        return TelemetryAck.scan(e.type(), result.status().name());
    }

    // First arrival of a loop seals the van in motion: LOADED → IN_PROGRESS (the hub depart already happened).
    private void startLoopIfNeeded(UUID vanId, int loopIndex, LocalDate date, Instant ts) {
        manifestRepository.findByVanIdAndLoopIndexAndValidDate(vanId, loopIndex, date).ifPresent(m -> {
            if (m.getStatus() == ManifestStatus.LOADED) {
                m.setStatus(ManifestStatus.IN_PROGRESS);
                if (m.getDepartedAt() == null) m.setDepartedAt(ts);
                manifestRepository.save(m);
            }
        });
    }

    // Lateness in whole minutes = actual wall-clock arrival − planned arrival; null if the stop has no
    // planned time to compare against. Never negative (early is "on time" for escalation).
    private Integer latenessVsPlan(UUID routePlanId, UUID vanId, int loopIndex, int stopSeq, Instant ts) {
        RoutePlanStop stop = stopRepository
                .findByRoutePlanIdAndVanIdAndLoopIndexOrderByStopSeq(routePlanId, vanId, loopIndex).stream()
                .filter(s -> s.getStopSeq() == stopSeq)
                .findFirst().orElse(null);
        if (stop == null || stop.getPlannedArrival() == null) return null;
        LocalTime actual = LocalTime.ofInstant(ts, clock.getZone());
        long late = Duration.between(stop.getPlannedArrival(), actual).toMinutes();
        return (int) Math.max(0, late);
    }

    private PlanCtx resolvePlanCtx(UUID vanId, Integer loopIndex, LocalDate date) {
        VanManifest manifest = (loopIndex != null
                ? manifestRepository.findByVanIdAndLoopIndexAndValidDate(vanId, loopIndex, date)
                : manifestRepository.findByVanIdAndValidDate(vanId, date).stream().findFirst())
                .orElse(null);
        if (manifest == null) return null;
        UUID cityId = planRepository.findById(manifest.getRoutePlanId()).map(RoutePlan::getCityId).orElse(null);
        return new PlanCtx(manifest.getRoutePlanId(), cityId);
    }

    private record PlanCtx(UUID routePlanId, UUID cityId) {
    }
}
