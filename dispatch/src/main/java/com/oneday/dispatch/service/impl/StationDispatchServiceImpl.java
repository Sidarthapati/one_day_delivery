package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.dto.response.TileQueueResponse;
import com.oneday.dispatch.dto.response.TileQueueResponse.DaQueueView;
import com.oneday.dispatch.dto.response.TileQueueResponse.TaskView;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.StationDispatchService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the station tile view. Today: DAs come from the in-memory territory and their live status;
 * their queues + the tile's deferred count come from the DB. Past dates: everything from the DB
 * (DAs discovered from the tile's rows; no per-date live status is retained).
 */
@Service
class StationDispatchServiceImpl implements StationDispatchService {

    private static final String PENDING = "PENDING";

    private final DispatchQueueRepository queueRepository;
    private final DeferredDispatchRepository deferredRepository;
    private final DaCronAssignmentRepository cronRepository;
    private final DaStatusService daStatusService;
    private final DispatchProperties props;

    StationDispatchServiceImpl(DispatchQueueRepository queueRepository,
                               DeferredDispatchRepository deferredRepository,
                               DaCronAssignmentRepository cronRepository,
                               DaStatusService daStatusService,
                               DispatchProperties props) {
        this.queueRepository = queueRepository;
        this.deferredRepository = deferredRepository;
        this.cronRepository = cronRepository;
        this.daStatusService = daStatusService;
        this.props = props;
    }

    @Override
    @Transactional(readOnly = true)
    public TileQueueResponse tileQueue(UUID tileId, LocalDate date, UUID scopeCityId) {
        boolean today = date.equals(LocalDate.now(ZoneId.of(props.getShift().getZone())));
        List<DispatchQueue> tileRows = queueRepository.findByTileIdAndOperatingDate(tileId, date);

        List<UUID> daIds = today
                ? daStatusService.dasForTile(tileId)
                : distinctDaIds(tileRows);

        enforceCityScope(scopeCityId, tileId, today, daIds, tileRows);

        Instant now = Instant.now();
        List<DaQueueView> das = new ArrayList<>();
        for (UUID daId : daIds) {
            das.add(buildDaView(daId, date, today, now));
        }
        int deferred = deferredRepository.countByTileIdAndOperatingDateAndStatus(tileId, date, PENDING);
        return new TileQueueResponse(tileId, date, das, deferred);
    }

    private DaQueueView buildDaView(UUID daId, LocalDate date, boolean today, Instant now) {
        DaLiveStatus live = today ? daStatusService.getLiveStatus(daId) : null;
        String status = live != null && live.getStatus() != null ? live.getStatus().name() : null;

        List<DispatchQueue> rows = new ArrayList<>(
                queueRepository.findByDaIdAndOperatingDateOrderByQueuePosition(daId, date));
        if (today) {
            rows.removeIf(r -> r.getStatus() != TaskStatus.QUEUED && r.getStatus() != TaskStatus.IN_PROGRESS);
        }

        Long cronSlack = cronRepository.findByDaIdAndOperatingDate(daId, date)
                .map(c -> Duration.between(now, c.getScheduledMeetingTime()).toMinutes())
                .orElse(null);

        List<TaskView> queue = rows.stream()
                .map(r -> new TaskView(r.getId(), r.getShipmentId(), r.getQueuePosition(),
                        r.getStatus().name(), r.getExpectedEta(), r.isCrossTerritory(), r.getTaskType().name()))
                .toList();
        return new DaQueueView(daId, status, queue.size(), cronSlack, queue);
    }

    private void enforceCityScope(UUID scopeCityId, UUID tileId, boolean today,
                                  List<UUID> daIds, List<DispatchQueue> tileRows) {
        if (scopeCityId == null) {
            return;   // ADMIN: no scope
        }
        UUID tileCity = resolveTileCity(today, daIds, tileRows);
        if (tileCity != null && !tileCity.equals(scopeCityId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tile " + tileId + " is outside the caller's city");
        }
    }

    /** The tile's city: from a live DA today, else from the tile's persisted rows; null if unknown. */
    private UUID resolveTileCity(boolean today, List<UUID> daIds, List<DispatchQueue> tileRows) {
        if (today) {
            for (UUID daId : daIds) {
                DaLiveStatus live = daStatusService.getLiveStatus(daId);
                if (live != null && live.getCityId() != null) {
                    return live.getCityId();
                }
            }
        }
        return tileRows.isEmpty() ? null : tileRows.get(0).getCityId();
    }

    private static List<UUID> distinctDaIds(List<DispatchQueue> tileRows) {
        Set<UUID> ids = new LinkedHashSet<>();
        tileRows.stream()
                .sorted(Comparator.comparingInt(DispatchQueue::getQueuePosition))
                .forEach(r -> ids.add(r.getDaId()));
        return new ArrayList<>(ids);
    }
}
