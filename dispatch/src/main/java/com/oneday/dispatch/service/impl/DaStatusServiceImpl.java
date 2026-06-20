package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.dispatch.service.model.DaQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory authority for live DA state (see {@link DaStatusService}). Three {@link ConcurrentHashMap}s
 * hold GPS/heartbeat, per-DA queues, and a per-DA lock; a dirty set drives the periodic flush so the
 * 2-minute batch only writes DAs that actually changed.
 */
@Service
class DaStatusServiceImpl implements DaStatusService {

    private static final Logger log = LoggerFactory.getLogger(DaStatusServiceImpl.class);

    private final ConcurrentHashMap<UUID, DaLiveStatus> liveStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, DaQueue> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** tile → DAs serving it today (reverse of the per-DA territory); drives candidate selection. */
    private final ConcurrentHashMap<UUID, Set<UUID>> dasByTile = new ConcurrentHashMap<>();
    /** da → tiles, kept so a re-register can withdraw a DA's old tile memberships. */
    private final ConcurrentHashMap<UUID, Set<UUID>> tilesByDa = new ConcurrentHashMap<>();
    /** DAs whose live state has changed since the last flush. */
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    private final DaStatusRepository daStatusRepository;
    private final DispatchProperties props;

    DaStatusServiceImpl(DaStatusRepository daStatusRepository, DispatchProperties props) {
        this.daStatusRepository = daStatusRepository;
        this.props = props;
    }

    @Override
    @Transactional
    public void initShift(UUID daId, UUID cityId, LocalDate shiftDate, String shiftType,
                          DaCronAssignment cronAssignment) {
        // Idempotent within a running process: a DA already in memory keeps its live state.
        if (liveStatus.containsKey(daId)) {
            return;
        }

        DaStatus row = daStatusRepository.findByDaId(daId).orElseGet(DaStatus::new);
        row.setDaId(daId);
        row.setCityId(cityId);
        boolean sameDayRestart = shiftDate.equals(row.getShiftDate()) && row.getStatus() != null;
        row.setShiftDate(shiftDate);
        row.setShiftType(shiftType);
        // Fresh shift day → reset to OFFLINE (no ping yet). Same-day pod restart → preserve status.
        if (!sameDayRestart) {
            row.setStatus(DaStatusEnum.OFFLINE);
            row.setQueueDepth(0);
            row.setLastHeartbeat(null);
            row.setLastGpsLat(null);
            row.setLastGpsLon(null);
        }
        daStatusRepository.save(row);

        DaLiveStatus live = new DaLiveStatus(daId, cityId, row.getLastGpsLat(), row.getLastGpsLon(),
                row.getLastHeartbeat(), row.getStatus());
        liveStatus.put(daId, live);
        queues.put(daId, new DaQueue(daId, cronAssignment));
        locks.computeIfAbsent(daId, k -> new ReentrantLock());
    }

    @Override
    public void updateGps(UUID daId, double lat, double lon, Instant timestamp) {
        DaLiveStatus live = liveStatus.get(daId);
        if (live == null) {
            log.debug("GPS ping for unloaded DA {} ignored", daId);
            return;
        }
        live.setLat(lat);
        live.setLon(lon);
        live.setLastHeartbeat(timestamp);
        dirty.add(daId);

        DaStatusEnum status = live.getStatus();
        // Heartbeat resumes a silent DA back to IDLE (absent-recovery, design §12.4).
        if (status == DaStatusEnum.OFFLINE || status == DaStatusEnum.ABSENT) {
            updateStatus(daId, DaStatusEnum.IDLE);
            return;
        }
        // Proximity to the cron vertex closes the CRON_LOCKED → AT_CRON transition.
        if (status == DaStatusEnum.CRON_LOCKED) {
            DaQueue q = queues.get(daId);
            DaCronAssignment cron = q != null ? q.getCron() : null;
            if (cron != null
                    && GeoDistance.meters(lat, lon, cron.getMeetingLat(), cron.getMeetingLon())
                       <= props.getCron().getProximityMeters()) {
                updateStatus(daId, DaStatusEnum.AT_CRON);
            }
        }
    }

    @Override
    @Transactional
    public void updateStatus(UUID daId, DaStatusEnum newStatus) {
        ReentrantLock lock = locks.computeIfAbsent(daId, k -> new ReentrantLock());
        lock.lock();
        try {
            DaLiveStatus live = liveStatus.get(daId);
            if (live != null) {
                live.setStatus(newStatus);
            }
            // Write the status column through synchronously — status changes (ABSENT, CRON_LOCKED)
            // must be durable immediately, not only at the next flush.
            daStatusRepository.findByDaId(daId).ifPresent(row -> {
                row.setStatus(newStatus);
                daStatusRepository.save(row);
            });
            dirty.add(daId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DaStatusEnum getStatus(UUID daId) {
        DaLiveStatus live = liveStatus.get(daId);
        return live != null ? live.getStatus() : null;
    }

    @Override
    public DaLiveStatus getLiveStatus(UUID daId) {
        return liveStatus.get(daId);
    }

    @Override
    public DaQueue getQueue(UUID daId) {
        return queues.get(daId);
    }

    @Override
    public synchronized void setTerritory(UUID daId, List<UUID> tileIds) {
        // Withdraw the DA from any tiles it no longer serves, then (re)register the new set.
        Set<UUID> previous = tilesByDa.getOrDefault(daId, Set.of());
        for (UUID old : previous) {
            Set<UUID> das = dasByTile.get(old);
            if (das != null) {
                das.remove(daId);
                if (das.isEmpty()) {
                    dasByTile.remove(old);
                }
            }
        }
        Set<UUID> tiles = Set.copyOf(tileIds);
        tilesByDa.put(daId, tiles);
        for (UUID tile : tiles) {
            dasByTile.computeIfAbsent(tile, k -> ConcurrentHashMap.newKeySet()).add(daId);
        }
    }

    @Override
    public List<UUID> dasForTile(UUID tileId) {
        Set<UUID> das = dasByTile.get(tileId);
        return das == null ? List.of() : List.copyOf(das);
    }

    @Override
    public <T> T withDaLock(UUID daId, java.util.function.Supplier<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(daId, k -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Set<UUID> loadedDaIds() {
        return Set.copyOf(liveStatus.keySet());
    }

    @Override
    public void clearAll() {
        liveStatus.clear();
        queues.clear();
        locks.clear();
        dasByTile.clear();
        tilesByDa.clear();
        dirty.clear();
    }

    @Override
    @Transactional
    @Scheduled(fixedDelayString = "${dispatch.gps.flush-interval-seconds:120}", timeUnit = TimeUnit.SECONDS)
    public void flushDirtyStatuses() {
        // Snapshot + drain: anything dirtied during the flush stays for the next run.
        List<UUID> batch = new ArrayList<>(dirty);
        if (batch.isEmpty()) {
            return;
        }
        List<DaStatus> toSave = new ArrayList<>(batch.size());
        for (UUID daId : batch) {
            dirty.remove(daId);
            DaLiveStatus live = liveStatus.get(daId);
            if (live == null) {
                continue;
            }
            daStatusRepository.findByDaId(daId).ifPresent(row -> {
                row.setLastGpsLat(live.getLat());
                row.setLastGpsLon(live.getLon());
                row.setLastHeartbeat(live.getLastHeartbeat());
                row.setStatus(live.getStatus());
                DaQueue q = queues.get(daId);
                row.setQueueDepth(q != null ? q.getTasks().size() : 0);
                toSave.add(row);
            });
        }
        if (!toSave.isEmpty()) {
            daStatusRepository.saveAll(toSave);
            log.debug("Flushed {} dirty DA status rows", toSave.size());
        }
    }
}
