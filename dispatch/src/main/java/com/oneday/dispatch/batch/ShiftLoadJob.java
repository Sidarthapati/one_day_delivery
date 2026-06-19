package com.oneday.dispatch.batch;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    private final GridService gridService;
    private final DaCronAssignmentRepository cronRepository;
    private final DispatchQueueRepository queueRepository;
    private final DaStatusService daStatusService;
    private final DispatchProperties props;

    public ShiftLoadJob(GridService gridService,
                        DaCronAssignmentRepository cronRepository,
                        DispatchQueueRepository queueRepository,
                        DaStatusService daStatusService,
                        DispatchProperties props) {
        this.gridService = gridService;
        this.cronRepository = cronRepository;
        this.queueRepository = queueRepository;
        this.daStatusService = daStatusService;
        this.props = props;
    }

    /** Scheduled trigger — fires {@code dispatch.shift.load-offset-minutes} before each shift start. */
    @Scheduled(cron = "${dispatch.shift.load-cron}", zone = "${dispatch.shift.zone}")
    public void onSchedule() {
        loadShiftsForDate(LocalDate.now());
    }

    /** Loads every configured city's roster for {@code date}. Package-visible for direct testing. */
    @Transactional
    public void loadShiftsForDate(LocalDate date) {
        for (String cityCode : props.getShift().getCities()) {
            try {
                loadCity(cityCode, date);
            } catch (RuntimeException e) {
                // One city failing must not starve the others.
                log.error("Shift load failed for city {} on {}", cityCode, date, e);
            }
        }
    }

    private void loadCity(String cityCode, LocalDate date) {
        UUID cityId = gridService.resolveCityId(cityCode);

        List<AssignmentResponse> assignments = gridService.getActiveAssignments(cityId, date);
        // daId → owned hexes. The key set IS the day's DA roster for this city.
        Map<UUID, List<UUID>> territoryByDa = assignments.stream()
                .collect(Collectors.groupingBy(AssignmentResponse::daId,
                        Collectors.mapping(AssignmentResponse::hexId, Collectors.toList())));

        Map<UUID, DaCronAssignment> cronByDa = cronRepository
                .findByOperatingDateAndCityId(date, cityId).stream()
                .collect(Collectors.toMap(DaCronAssignment::getDaId, c -> c, (a, b) -> a));

        for (Map.Entry<UUID, List<UUID>> entry : territoryByDa.entrySet()) {
            UUID daId = entry.getKey();
            recoverStaleEtas(daId, date);
            // shiftType is left null until an HR/ops shift-roster source exists (design §12.1).
            daStatusService.initShift(daId, cityId, date, null, cronByDa.get(daId));
        }

        log.info("Shift load {} {}: {} DAs, {} with cron, {} hexes assigned",
                cityCode, date, territoryByDa.size(), cronByDa.size(),
                assignments.stream().map(AssignmentResponse::hexId).distinct().count());
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
