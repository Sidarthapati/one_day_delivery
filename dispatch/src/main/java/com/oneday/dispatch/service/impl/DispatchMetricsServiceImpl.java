package com.oneday.dispatch.service.impl;

import com.oneday.common.port.DaDirectoryPort;
import com.oneday.common.port.DaDirectoryPort.DaContact;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.dto.response.DaDetailResponse;
import com.oneday.dispatch.dto.response.DaDetailResponse.DaTaskItem;
import com.oneday.dispatch.dto.response.DaDetailResponse.DayStops;
import com.oneday.dispatch.dto.response.DaScorecard;
import com.oneday.dispatch.dto.response.DispatchExecutionStats;
import com.oneday.dispatch.dto.response.DispatchExecutionStats.DaPace;
import com.oneday.dispatch.repository.DaDayStopsRow;
import com.oneday.dispatch.repository.DaPaceRow;
import com.oneday.dispatch.repository.DaScorecardRow;
import com.oneday.dispatch.repository.DeliveryOutcome;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DispatchMetricsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
class DispatchMetricsServiceImpl implements DispatchMetricsService {

    private static final int HISTORY_DAYS = 7;

    private final DispatchQueueRepository queueRepository;
    private final DaDirectoryPort daDirectory;

    DispatchMetricsServiceImpl(DispatchQueueRepository queueRepository, DaDirectoryPort daDirectory) {
        this.queueRepository = queueRepository;
        this.daDirectory = daDirectory;
    }

    @Override
    @Transactional(readOnly = true)
    public DispatchExecutionStats execution(LocalDate date, UUID scopeCityId) {
        DeliveryOutcome outcome = queueRepository.deliveryOutcome(scopeCityId, date);
        long completed = outcome != null ? outcome.getCompleted() : 0;
        long failed = outcome != null ? outcome.getFailed() : 0;
        long attempts = completed + failed;
        Double successPct = attempts == 0 ? null : (double) completed / attempts;

        Instant now = Instant.now();
        List<DaPaceRow> rows = queueRepository.paceByDa(scopeCityId, date);
        Map<UUID, DaContact> contacts = daDirectory.contactsFor(rows.stream().map(DaPaceRow::getDaId).toList());
        List<DaPace> das = rows.stream()
                .map(r -> toPace(r, now, contacts.get(r.getDaId())))
                .sorted(Comparator.comparingLong(DaPace::stopsPending).reversed()
                        .thenComparing(Comparator.comparingLong(DaPace::stopsDone).reversed()))
                .toList();

        return new DispatchExecutionStats(date, successPct, completed, failed, das);
    }

    @Override
    @Transactional(readOnly = true)
    public DaDetailResponse daDetail(UUID daId, LocalDate date, UUID scopeCityId) {
        List<DispatchQueue> all = queueRepository.findByDaIdAndOperatingDateOrderByQueuePosition(daId, date);
        // City scope: a station manager only inspects a DA operating in their city today.
        List<DispatchQueue> tasks = scopeCityId == null ? all
                : all.stream().filter(t -> scopeCityId.equals(t.getCityId())).toList();
        if (scopeCityId != null && tasks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No tasks for this DA in your city today");
        }

        DaContact contact = daDirectory.contactsFor(List.of(daId)).get(daId);
        String name = contact != null ? contact.name() : null;
        String phone = contact != null ? contact.phone() : null;
        UUID cityId = tasks.isEmpty() ? scopeCityId : tasks.get(0).getCityId();

        Instant now = Instant.now();
        DaPace pace = queueRepository.paceByDa(scopeCityId, date).stream()
                .filter(r -> r.getDaId().equals(daId))
                .findFirst()
                .map(r -> toPace(r, now, contact))
                .orElse(new DaPace(daId, name, phone, 0, 0, 0, 0.0));

        long completed = tasks.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
        long failed = tasks.stream().filter(t -> t.getStatus() == TaskStatus.FAILED).count();

        List<DaTaskItem> items = tasks.stream()
                .map(t -> new DaTaskItem(t.getId(), t.getShipmentId(), t.getTaskType().name(),
                        t.getStatus().name(), t.getExpectedEta(), urgency(t, now)))
                .sorted(Comparator.comparingInt(i -> urgencyRank(i.urgency())))
                .toList();

        List<DayStops> history = buildHistory(daId, date, scopeCityId);

        return new DaDetailResponse(daId, name, phone, cityId, date, pace, completed, failed, items, history);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaScorecard> scorecards(LocalDate date, UUID scopeCityId) {
        Instant now = Instant.now();
        List<DaScorecardRow> rows = queueRepository.scorecardByDa(scopeCityId, date);
        Map<UUID, DaContact> contacts = daDirectory.contactsFor(rows.stream().map(DaScorecardRow::getDaId).toList());
        return rows.stream()
                .map(r -> toScorecard(r, now, contacts.get(r.getDaId())))
                // busiest first: most stops done, then most still pending
                .sorted(Comparator.comparingLong(DaScorecard::stopsDone).reversed()
                        .thenComparing(Comparator.comparingLong(DaScorecard::stopsPending).reversed()))
                .toList();
    }

    private static DaScorecard toScorecard(DaScorecardRow r, Instant now, DaContact contact) {
        long attempts = r.getDone() + r.getFailed();
        Double attemptSuccess = attempts == 0 ? null : (double) r.getDone() / attempts;
        Double onTime = r.getDone() == 0 ? null : (double) r.getOnTime() / r.getDone();
        return new DaScorecard(r.getDaId(),
                contact != null ? contact.name() : null,
                contact != null ? contact.phone() : null,
                r.getDone(), r.getFailed(), r.getPending(),
                avgPerHour(r.getDone(), r.getFirstAssigned(), now),
                attemptSuccess, onTime);
    }

    /** RED = failed or an active task already past its ETA; AMBER = in-progress on-track; GREEN = queued
     *  on-track; DONE = completed/cancelled. Uses expectedEta as the on-time signal dispatch owns. */
    private static String urgency(DispatchQueue t, Instant now) {
        boolean pastEta = t.getExpectedEta() != null && now.isAfter(t.getExpectedEta());
        return switch (t.getStatus()) {
            case COMPLETED, CANCELLED -> "DONE";
            case FAILED -> "RED";
            case IN_PROGRESS -> pastEta ? "RED" : "AMBER";
            case QUEUED, DEFERRED -> pastEta ? "RED" : "GREEN";
        };
    }

    /**
     * Exactly {@code HISTORY_DAYS} entries, from {@code date.minusDays(HISTORY_DAYS-1)} through
     * {@code date}, oldest first: days a DA ran no tasks are filled with zeroes (so the trend chart
     * has one bar per day), and the upper bound keeps a request for a past date from leaking later days.
     */
    private List<DayStops> buildHistory(UUID daId, LocalDate date, UUID scopeCityId) {
        LocalDate from = date.minusDays(HISTORY_DAYS - 1L);
        Map<LocalDate, DaDayStopsRow> byDay = queueRepository.paceByDaOverDays(daId, from, scopeCityId).stream()
                .filter(r -> !r.getDay().isAfter(date))
                .collect(Collectors.toMap(DaDayStopsRow::getDay, r -> r, (a, b) -> a));
        return IntStream.range(0, HISTORY_DAYS)
                .mapToObj(from::plusDays)
                .map(d -> {
                    DaDayStopsRow r = byDay.get(d);
                    return r == null ? new DayStops(d, 0, 0) : new DayStops(d, r.getDone(), r.getFailed());
                })
                .toList();
    }

    private static int urgencyRank(String urgency) {
        return switch (urgency) {
            case "RED" -> 0;
            case "AMBER" -> 1;
            case "GREEN" -> 2;
            default -> 3; // DONE
        };
    }

    private static DaPace toPace(DaPaceRow r, Instant now, DaContact contact) {
        return new DaPace(r.getDaId(),
                contact != null ? contact.name() : null,
                contact != null ? contact.phone() : null,
                r.getDone(), r.getLastHour(), r.getPending(),
                avgPerHour(r.getDone(), r.getFirstAssigned(), now));
    }

    /** Stops done over hours on shift (since first assignment). Guards a fresh DA: &lt;6 min elapsed → 0,
     *  so one early stop doesn't read as an implausible 60/hr. */
    private static double avgPerHour(long done, Instant firstAssigned, Instant now) {
        if (done == 0 || firstAssigned == null) {
            return 0.0;
        }
        double hours = Duration.between(firstAssigned, now).toSeconds() / 3600.0;
        if (hours < 0.1) {
            return 0.0;
        }
        return done / hours;
    }
}
