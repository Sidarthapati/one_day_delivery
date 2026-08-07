package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueReorderServiceTest {

    private DispatchQueueRepository queueRepo;
    private DaCronAssignmentRepository cronRepo;
    private DaStatusService daStatus;
    private DaEventProducer events;
    private QueueReorderService service;

    private final UUID daId = UUID.randomUUID();
    private final UUID city = UUID.randomUUID();
    private final LocalDate date = LocalDate.now();

    @BeforeEach
    void setUp() {
        queueRepo = mock(DispatchQueueRepository.class);
        cronRepo = mock(DaCronAssignmentRepository.class);
        daStatus = mock(DaStatusService.class);
        events = mock(DaEventProducer.class);
        service = new QueueReorderService(queueRepo, cronRepo, daStatus, events, new DispatchProperties());
        when(cronRepo.findByDaIdAndOperatingDate(daId, date)).thenReturn(Optional.empty()); // no cron
        // Anchor at (0,0) — the DA's GPS (no in-progress task).
        when(daStatus.getLiveStatus(daId))
                .thenReturn(new DaLiveStatus(daId, city, 0.0, 0.0, null, DaStatusEnum.IDLE, "SHIFT_1"));
    }

    @Test
    void agedFarTaskOvertakesFreshNearTask() {
        // Near + fresh at pos 0; far + 2h30m old at pos 1. Aging past saturation must promote the far one.
        DispatchQueue nearFresh = queued(0, 0.01, 0.01, Instant.now());
        DispatchQueue farOld = queued(1, 0.3, 0.3, Instant.now().minus(150, ChronoUnit.MINUTES));
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(eq(daId), eq(date), any()))
                .thenReturn(List.of(nearFresh, farOld));

        service.reorder(daId, date);

        assertThat(farOld.getQueuePosition()).isEqualTo(0);    // starved task jumps the queue
        assertThat(nearFresh.getQueuePosition()).isEqualTo(1);
        verify(events).emitQueueReordered(daId, city);
    }

    @Test
    void inProgressHeadIsNeverMoved() {
        DispatchQueue head = inProgress(0, 0.5, 0.5);          // the task the DA is doing
        DispatchQueue nearFresh = queued(1, 0.01, 0.01, Instant.now());
        DispatchQueue farOld = queued(2, 0.3, 0.3, Instant.now().minus(150, ChronoUnit.MINUTES));
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(eq(daId), eq(date), any()))
                .thenReturn(List.of(head, nearFresh, farOld));

        service.reorder(daId, date);

        assertThat(head.getQueuePosition()).isEqualTo(0);      // pinned
        assertThat(farOld.getQueuePosition()).isEqualTo(1);    // reordered tail starts after the head
        assertThat(nearFresh.getQueuePosition()).isEqualTo(2);
    }

    private DispatchQueue queued(int pos, double lat, double lon, Instant assignedAt) {
        return row(pos, lat, lon, assignedAt, TaskStatus.QUEUED);
    }

    private DispatchQueue inProgress(int pos, double lat, double lon) {
        return row(pos, lat, lon, Instant.now(), TaskStatus.IN_PROGRESS);
    }

    private DispatchQueue row(int pos, double lat, double lon, Instant assignedAt, TaskStatus status) {
        DispatchQueue q = new DispatchQueue();
        q.setDaId(daId);
        q.setCityId(city);
        q.setShipmentId(UUID.randomUUID());
        q.setTaskType(TaskType.PICKUP);
        q.setTaskLat(lat);
        q.setTaskLon(lon);
        q.setQueuePosition(pos);
        q.setStatus(status);
        q.setAssignedAt(assignedAt);
        return q;
    }
}
