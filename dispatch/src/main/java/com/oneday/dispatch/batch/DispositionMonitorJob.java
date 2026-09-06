package com.oneday.dispatch.batch;

import com.oneday.dispatch.service.DaDispositionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Drives the disposition overstay clock: bumps the in-app escalation banner past each break's scheduled
 * end, marks a break OVERSTAYED (→ surfaced to the station manager) once past the grace window, and
 * closes any break the DA has effectively left (cron freeze took over, or a manager marked them absent).
 * It never vacates territory — that stays the manager's mark-absent action.
 */
@Component
public class DispositionMonitorJob {

    private final DaDispositionService service;

    public DispositionMonitorJob(DaDispositionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dispatch.disposition.sweep-seconds:60}", timeUnit = TimeUnit.SECONDS)
    public void run() {
        service.sweep(Instant.now());
    }
}
