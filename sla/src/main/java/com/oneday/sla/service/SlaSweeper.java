package com.oneday.sla.service;

import com.oneday.sla.domain.SlaShipment;
import com.oneday.sla.repository.SlaShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Time is a signal events can't send. A leg can blow its deadline while nothing happens — no state
 * change, no scan. This periodically re-evaluates every open shipment so a silent overrun still flips
 * AMBER→RED and escalates. Cheap at pilot volume; recompute is idempotent.
 */
@Component
public class SlaSweeper {

    private static final Logger log = LoggerFactory.getLogger(SlaSweeper.class);

    private final SlaShipmentRepository shipmentRepo;
    private final SlaEngine engine;

    public SlaSweeper(SlaShipmentRepository shipmentRepo, SlaEngine engine) {
        this.shipmentRepo = shipmentRepo;
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${sla.sweeper-fixed-delay-ms:60000}")
    public void sweep() {
        List<SlaShipment> open = shipmentRepo.findByClosedAtIsNull();
        if (open.isEmpty()) {
            return;
        }
        int errors = 0;
        for (SlaShipment ss : open) {
            try {
                engine.recompute(ss.getShipmentId());
            } catch (RuntimeException ex) {
                errors++;
                log.warn("SLA sweep failed for shipment {}: {}", ss.getShipmentId(), ex.toString());
            }
        }
        log.debug("SLA sweep evaluated {} open shipments ({} errors)", open.size(), errors);
    }
}
