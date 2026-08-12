package com.oneday.hub.service;

import com.oneday.hub.config.HubProperties;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.repository.FlightBagRepository;
import com.oneday.common.log.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * M12 backstop: a bag can never miss its flight because a human (hub operator or shuttle agent) forgot
 * to seal it. This sweeps for still-OPEN bags whose cutoff is within {@code autoSealBufferMinutes} and
 * seals them via the normal {@link FlightBagService#seal} path (so the manifest + BAG_SEALED fire as
 * usual). Safe because past cutoff no new parcel can make the flight anyway.
 */
@Component
class AutoSealJob {

    private static final Logger log = LoggerFactory.getLogger(AutoSealJob.class);

    private final FlightBagRepository flightBagRepository;
    private final FlightBagService flightBagService;
    private final HubProperties properties;
    private final Clock clock;

    AutoSealJob(FlightBagRepository flightBagRepository, FlightBagService flightBagService,
                HubProperties properties, Clock clock) {
        this.flightBagRepository = flightBagRepository;
        this.flightBagService = flightBagService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hub.auto-seal-interval-ms:120000}")
    void sweep() {
        Instant threshold = clock.instant().plus(properties.getAutoSealBufferMinutes(), ChronoUnit.MINUTES);
        List<FlightBag> due = flightBagRepository.findByStatusAndBagCutoffBefore(FlightBagStatus.OPEN, threshold);
        for (FlightBag bag : due) {
            try {
                flightBagService.seal(bag.getId());
                AuditLog.event("bag.auto_sealed")
                        .kv("bagId", bag.getId()).kv("flightNo", bag.getFlightNo())
                        .kv("bagCutoff", bag.getBagCutoff()).log();
            } catch (RuntimeException e) {
                // A bag that raced to SEALED/DISPATCHED, or has no free manifest — never let one bag
                // fail the sweep for the rest.
                log.warn("Auto-seal skipped bag {} ({}): {}", bag.getId(), bag.getFlightNo(), e.getMessage());
            }
        }
    }
}
