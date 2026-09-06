package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaLocationStub;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.StubStatus;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.repository.DaLocationStubRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.LocationStubService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Location-stub lifecycle. Find-or-create is safe without extra locking because every entry point runs
 * under the DA lock the dispatch/task services already hold (assignment and all task transitions), so no
 * two writers touch a DA's stubs at once. Rollups use atomic {@code @Modifying} UPDATEs; close is a
 * load-modify-save on the (now single-writer) stub.
 */
@Service
class LocationStubServiceImpl implements LocationStubService {

    private final DaLocationStubRepository stubRepository;
    private final DispatchQueueRepository queueRepository;
    private final DispatchProperties props;

    LocationStubServiceImpl(DaLocationStubRepository stubRepository,
                            DispatchQueueRepository queueRepository,
                            DispatchProperties props) {
        this.stubRepository = stubRepository;
        this.queueRepository = queueRepository;
        this.props = props;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(DispatchQueue row) {
        String key = locationKey(row.getTaskLat(), row.getTaskLon());
        DaLocationStub stub = stubRepository
                .findFirstByDaIdAndOperatingDateAndLocationKeyAndStatus(
                        row.getDaId(), row.getOperatingDate(), key, StubStatus.OPEN)
                .orElseGet(() -> openStub(row, key));
        row.setStubId(stub.getId());
        stubRepository.incrementTaskCount(stub.getId());
    }

    private DaLocationStub openStub(DispatchQueue row, String key) {
        DaLocationStub stub = new DaLocationStub();
        stub.setDaId(row.getDaId());
        stub.setCityId(row.getCityId());
        stub.setOperatingDate(row.getOperatingDate());
        stub.setLocationKey(key);
        stub.setStubLat(row.getTaskLat());
        stub.setStubLon(row.getTaskLon());
        stub.setTileId(row.getTileId());
        stub.setStatus(StubStatus.OPEN);
        stub.setOpenedAt(Instant.now());
        return stubRepository.save(stub);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onArrived(DispatchQueue task) {
        if (task.getStubId() == null) {
            return;   // legacy/pre-stub task
        }
        Instant ts = task.getArrivedAt() != null ? task.getArrivedAt() : Instant.now();
        stubRepository.recordArrival(task.getStubId(), ts);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTerminal(DispatchQueue task) {
        if (task.getStubId() == null) {
            return;   // legacy/pre-stub task
        }
        Instant ts = task.getCompletedAt() != null ? task.getCompletedAt() : Instant.now();
        if (task.getStatus() == TaskStatus.COMPLETED) {
            stubRepository.recordProcessed(task.getStubId(), ts);
        } else if (task.getStatus() == TaskStatus.FAILED) {
            stubRepository.recordFailed(task.getStubId(), ts);
        }
        // CANCELLED (e.g. a reschedule recall) removes work without counting or advancing dwell, but can
        // still be the last open task — fall through to the close check either way.
        closeIfDone(task.getStubId());
    }

    /** Close the visit once no task in it is still open (all COMPLETED/FAILED/CANCELLED). */
    private void closeIfDone(java.util.UUID stubId) {
        if (queueRepository.countOpenTasksInStub(stubId) > 0) {
            return;
        }
        stubRepository.findById(stubId).ifPresent(stub -> {
            if (stub.getStatus() == StubStatus.CLOSED) {
                return;
            }
            stub.setStatus(StubStatus.CLOSED);
            stub.setClosedAt(Instant.now());
            stub.setDwellSeconds(dwellSeconds(stub));
            stubRepository.save(stub);
        });
    }

    /** {@code lastCompletedAt - firstArrivedAt} in seconds, or null if either end is missing/inverted. */
    static Long dwellSeconds(DaLocationStub stub) {
        Instant arrived = stub.getFirstArrivedAt();
        Instant done = stub.getLastCompletedAt();
        if (arrived == null || done == null || done.isBefore(arrived)) {
            return null;
        }
        return Duration.between(arrived, done).getSeconds();
    }

    /** "lat,lon" rounded to the configured precision (5dp ≈ 1.1m) — matches the DA app's grouping. */
    private String locationKey(double lat, double lon) {
        int p = props.getStub().getCoordPrecision();
        return String.format(Locale.ROOT, "%." + p + "f,%." + p + "f", lat, lon);
    }
}
