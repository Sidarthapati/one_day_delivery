package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.service.DaStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the in-memory state authority — the repository is mocked, so these exercise
 * the map/dirty/flush logic without a database (the schema itself is covered by the PR #3
 * @DataJpaTest).
 */
class DaStatusServiceImplTest {

    private DaStatusRepository repo;
    private DaStatusService service;
    private final Map<UUID, DaStatus> db = new HashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(DaStatusRepository.class);
        db.clear();
        // Stub the repo as a tiny in-memory store keyed by daId so save/find behave realistically.
        when(repo.findByDaId(any())).thenAnswer(inv -> Optional.ofNullable(db.get(inv.getArgument(0))));
        when(repo.save(any(DaStatus.class))).thenAnswer(inv -> {
            DaStatus s = inv.getArgument(0);
            db.put(s.getDaId(), s);
            return s;
        });
        when(repo.saveAll(anyList())).thenAnswer(inv -> {
            List<DaStatus> all = inv.getArgument(0);
            all.forEach(s -> db.put(s.getDaId(), s));
            return all;
        });

        DispatchProperties props = new DispatchProperties();   // defaults: 200m proximity
        service = new DaStatusServiceImpl(repo, props);
    }

    @Test
    void initShiftIsIdempotent() {
        UUID da = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        service.initShift(da, city, today, "A", null);
        // Move it forward in life so a second init would be destructive if it ran.
        service.updateGps(da, 12.97, 77.61, Instant.now());   // OFFLINE → IDLE
        assertThat(service.getStatus(da)).isEqualTo(DaStatusEnum.IDLE);

        // Second call (e.g. duplicate shift-load) must be a no-op on live state.
        service.initShift(da, city, today, "A", null);
        assertThat(service.getStatus(da)).isEqualTo(DaStatusEnum.IDLE);
    }

    @Test
    void flushOnlyWritesDirtyDas() {
        UUID dirtyDa = UUID.randomUUID();
        UUID cleanDa = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        service.initShift(dirtyDa, city, today, "A", null);
        service.initShift(cleanDa, city, today, "A", null);

        // Only dirtyDa receives a GPS ping → only it is dirty.
        service.updateGps(dirtyDa, 12.97, 77.61, Instant.parse("2026-06-20T06:01:00Z"));

        service.flushDirtyStatuses();

        DaStatus flushed = db.get(dirtyDa);
        assertThat(flushed.getLastGpsLat()).isEqualTo(12.97);
        assertThat(flushed.getStatus()).isEqualTo(DaStatusEnum.IDLE);
        // cleanDa never moved past its init OFFLINE state and carries no GPS.
        assertThat(db.get(cleanDa).getLastGpsLat()).isNull();
        assertThat(db.get(cleanDa).getStatus()).isEqualTo(DaStatusEnum.OFFLINE);
    }

    @Test
    void secondFlushWithoutChangesWritesNothing() {
        UUID da = UUID.randomUUID();
        service.initShift(da, UUID.randomUUID(), LocalDate.now(), "A", null);
        service.updateGps(da, 1.0, 2.0, Instant.now());
        service.flushDirtyStatuses();

        // Nothing changed since the last flush → the dirty set is empty → no write.
        org.mockito.Mockito.clearInvocations(repo);
        service.flushDirtyStatuses();
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    void gpsWithinProximityWhileCronLockedFlipsToAtCron() {
        UUID da = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        DaCronAssignment cron = cronAt(12.9716, 77.5946);

        service.initShift(da, city, today, "A", cron);
        service.updateStatus(da, DaStatusEnum.CRON_LOCKED);

        // ~30 m away (well inside the 200 m default) → AT_CRON.
        service.updateGps(da, 12.97187, 77.5946, Instant.now());
        assertThat(service.getStatus(da)).isEqualTo(DaStatusEnum.AT_CRON);
    }

    @Test
    void gpsOutsideProximityWhileCronLockedStaysLocked() {
        UUID da = UUID.randomUUID();
        DaCronAssignment cron = cronAt(12.9716, 77.5946);

        service.initShift(da, UUID.randomUUID(), LocalDate.now(), "A", cron);
        service.updateStatus(da, DaStatusEnum.CRON_LOCKED);

        // ~1.1 km away → still CRON_LOCKED.
        service.updateGps(da, 12.9816, 77.5946, Instant.now());
        assertThat(service.getStatus(da)).isEqualTo(DaStatusEnum.CRON_LOCKED);
    }

    /** Builds a DaCronAssignment positioned at the given meeting point (no DB needed). */
    private static DaCronAssignment cronAt(double lat, double lon) {
        DaCronAssignment c = new DaCronAssignment();
        c.setMeetingLat(lat);
        c.setMeetingLon(lon);
        return c;
    }
}
