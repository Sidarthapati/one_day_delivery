package com.oneday.dispatch.batch;

import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.DeferredDispatch;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Start-of-day bootstrap: reads M3's DA→hex assignments (the roster) and M6's cron schedule, then
 * seeds {@link DaStatusService}'s in-memory maps with an empty queue per DA. Tasks arrive later as
 * shipment events flow in (Phase 3) — at load time the queues are intentionally empty.
 *
 * <p>Cross-module reads go through public service interfaces only: M3 via {@link GridService}
 * (in-process), M6 via the dispatch-local {@code da_cron_assignment} table (populated upstream from
 * M6's {@code DaCronScheduledEvent}).</p>
 */
@Component
public class ShiftLoadJob {

    private static final Logger log = LoggerFactory.getLogger(ShiftLoadJob.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final GridService gridService;
    private final DaDirectoryPort daDirectoryPort;
    private final DaCronAssignmentRepository cronRepository;
    private final DispatchQueueRepository queueRepository;
    private final DeferredDispatchRepository deferredRepository;
    private final DaStatusService daStatusService;
    private final DispatchProperties props;

    public ShiftLoadJob(GridService gridService,
                        DaDirectoryPort daDirectoryPort,
                        DaCronAssignmentRepository cronRepository,
                        DispatchQueueRepository queueRepository,
                        DeferredDispatchRepository deferredRepository,
                        DaStatusService daStatusService,
                        DispatchProperties props) {
        this.gridService = gridService;
        this.daDirectoryPort = daDirectoryPort;
        this.cronRepository = cronRepository;
        this.queueRepository = queueRepository;
        this.deferredRepository = deferredRepository;
        this.daStatusService = daStatusService;
        this.props = props;
    }

    /** Scheduled trigger — fires ~15 min before each shift start (05:45 → SHIFT_1, 13:45 → SHIFT_2). */
    @Scheduled(cron = "${dispatch.shift.load-cron:0 45 5,13 * * *}", zone = "${dispatch.shift.zone:Asia/Kolkata}")
    public void onSchedule() {
        loadShiftsForDate(LocalDate.now(IST), currentLoadingShift());
    }

    /**
     * On boot, load the current shift's roster so a restart mid-day (deploy, crash, scale event)
     * rehydrates the in-memory roster instead of leaving it empty until the next scheduled load.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            loadShiftsForDate(LocalDate.now(IST), currentLoadingShift());
        } catch (RuntimeException e) {
            log.error("Startup shift load failed", e);
        }
    }

    /** Which shift the current fire time is loading (before noon = SHIFT_1, else SHIFT_2). */
    static Shift currentLoadingShift() {
        return LocalTime.now(IST).getHour() < 12 ? Shift.SHIFT_1 : Shift.SHIFT_2;
    }

    /** Loads every configured city's roster for {@code date} + {@code shift}. Package-visible for testing. */
    @Transactional
    public void loadShiftsForDate(LocalDate date, Shift shift) {
        for (String cityCode : props.getShift().getCities()) {
            try {
                loadCity(cityCode, date, shift);
            } catch (RuntimeException e) {
                // One city failing must not starve the others.
                log.error("Shift load failed for city {} shift {} on {}", cityCode, shift, date, e);
            }
        }
    }

    private void loadCity(String cityCode, LocalDate date, Shift shift) {
        UUID cityId = gridService.resolveCityId(cityCode);

        // A carry-over (SHIFT_ENDED / still-pending) deferral gets a fresh retry budget as the new shift's
        // DA comes on the clock, so it isn't escalated to M11 for exhausting yesterday's/the prior shift's
        // retries before this shift's DA has had a chance.
        resetRetryBudget(cityId);

        // The roster for THIS shift: DAs registered to the shift whose contract covers the date.
        Set<UUID> shiftRoster = new HashSet<>(daDirectoryPort.availableDaIds(cityCode, date, shift));

        List<AssignmentResponse> assignments = gridService.getActiveAssignments(cityId, date);
        // daId → owned hexes, restricted to this shift's roster (a DA belongs to exactly one shift, so
        // the other shift's approved assignments — present for the same date — are filtered out here).
        Map<UUID, List<UUID>> territoryByDa = assignments.stream()
                .filter(a -> shiftRoster.contains(a.daId()))
                .collect(Collectors.groupingBy(AssignmentResponse::daId,
                        Collectors.mapping(AssignmentResponse::hexId, Collectors.toList())));

        Map<UUID, DaCronAssignment> cronByDa = cronRepository
                .findByOperatingDateAndCityId(date, cityId).stream()
                .collect(Collectors.toMap(DaCronAssignment::getDaId, c -> c, (a, b) -> a));

        for (Map.Entry<UUID, List<UUID>> entry : territoryByDa.entrySet()) {
            UUID daId = entry.getKey();
            recoverStaleEtas(daId, date);
            daStatusService.initShift(daId, cityId, date, shift.name(), cronByDa.get(daId));
            // Register the DA's hexes so the assignment engine can find candidate DAs by tile.
            daStatusService.setTerritory(daId, entry.getValue());
        }

        log.info("Shift load {} {} {}: {} DAs, {} with cron, {} hexes assigned",
                cityCode, shift, date, territoryByDa.size(), cronByDa.size(),
                assignments.stream().map(AssignmentResponse::hexId).distinct().count());
    }

    /**
     * Give this city's still-PENDING deferrals a fresh retry budget as a new shift comes on. Without this a
     * carry-over that exhausted the prior shift's {@code max-retries} would escalate to M11 the moment the
     * new shift's retries resume, instead of getting a real chance with the incoming DA.
     */
    private void resetRetryBudget(UUID cityId) {
        List<DeferredDispatch> pending = deferredRepository.findByCityIdAndStatus(cityId, "PENDING");
        if (pending.isEmpty()) {
            return;
        }
        for (DeferredDispatch d : pending) {
            d.setRetryCount(0);
            d.setRetryAfter(null);
        }
        deferredRepository.saveAll(pending);
        log.info("Reset retry budget for {} pending deferrals in city {}", pending.size(), cityId);
    }

    /**
     * Restart recovery: an {@code IN_PROGRESS} task whose {@code expected_eta} is already in the past
     * (the pod died mid-task) gets its ETA reset to now, so downstream sequencing treats it as
     * due-now rather than overdue.
     */
    private void recoverStaleEtas(UUID daId, LocalDate date) {
        List<DispatchQueue> inProgress = queueRepository
                .findByDaIdAndOperatingDateAndStatusIn(daId, date, List.of(TaskStatus.IN_PROGRESS));
        Instant now = Instant.now();
        List<DispatchQueue> stale = inProgress.stream()
                .filter(t -> t.getExpectedEta() != null && t.getExpectedEta().isBefore(now))
                .peek(t -> t.setExpectedEta(now))
                .collect(Collectors.toList());
        if (!stale.isEmpty()) {
            queueRepository.saveAll(stale);
            log.info("Restart recovery: reset {} stale ETAs for DA {}", stale.size(), daId);
        }
    }
}
