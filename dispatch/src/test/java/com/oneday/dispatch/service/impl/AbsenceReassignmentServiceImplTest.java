package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.AbsenceStatus;
import com.oneday.dispatch.domain.DaAbsenceEvent;
import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.dto.response.AbsenceApplyResponse;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse;
import com.oneday.dispatch.repository.DaAbsenceEventRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.grid.dto.response.AbsenceReassignmentPlan;
import com.oneday.grid.dto.response.AbsenceReassignmentPlan.HexReassignment;
import com.oneday.grid.service.GridService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbsenceReassignmentServiceImplTest {

    private static final UUID CITY = UUID.randomUUID();
    private static final UUID RAVI = UUID.randomUUID();
    private static final UUID MEENA = UUID.randomUUID();
    private static final UUID TILE_A = UUID.randomUUID();
    private static final UUID TILE_ORPHAN = UUID.randomUUID();

    @Mock private GridService gridService;
    @Mock private DispatchQueueRepository queueRepository;
    @Mock private DaAbsenceEventRepository absenceRepository;
    @Mock private DaStatusService daStatusService;
    @Mock private com.oneday.dispatch.repository.DaStatusRepository daStatusRepository;
    @Mock private com.oneday.common.port.DaDirectoryPort daDirectory;
    @Mock private QueueReorderService reorderService;

    private AbsenceReassignmentServiceImpl service;

    @BeforeEach
    void setUp() {
        DispatchProperties props = new DispatchProperties();
        props.getShift().setZone("Asia/Kolkata");
        props.getAbsence().setAutoApproveTimeoutMinutes(5);
        service = new AbsenceReassignmentServiceImpl(gridService, queueRepository, absenceRepository,
                daStatusService, daStatusRepository, daDirectory, reorderService, props);
    }

    private AbsenceReassignmentPlan planWithTileAToMeena() {
        return new AbsenceReassignmentPlan(CITY, LocalDate.now(), List.of(RAVI),
                List.of(new HexReassignment(TILE_A, RAVI, MEENA)), List.of());
    }

    private DispatchQueue row(UUID tile, TaskStatus status, TaskType type) {
        DispatchQueue r = new DispatchQueue();
        r.setDaId(RAVI);
        r.setCityId(CITY);
        r.setShipmentId(UUID.randomUUID());
        r.setTaskType(type);
        r.setTaskLat(28.6);
        r.setTaskLon(77.2);
        r.setTileId(tile);
        r.setStatus(status);
        r.setQueuePosition(0);
        return r;
    }

    @Test
    void previewSplitsTasksIntoLooseCustodyAndOrphanBuckets() {
        stubShiftScope();
        when(gridService.planAbsenceReassignment(eq(CITY), eq(List.of(RAVI)), any(), any()))
                .thenReturn(planWithTileAToMeena());
        when(queueRepository.findByDaIdAndOperatingDateAndStatusIn(eq(RAVI), any(), any()))
                .thenReturn(List.of(
                        row(TILE_A, TaskStatus.QUEUED, TaskType.DELIVERY),        // loose
                        row(TILE_A, TaskStatus.IN_PROGRESS, TaskType.DELIVERY),   // in custody
                        row(TILE_ORPHAN, TaskStatus.QUEUED, TaskType.PICKUP)));   // orphan hex
        when(absenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AbsencePreviewResponse preview = service.preview(CITY, List.of(RAVI), "sick", UUID.randomUUID());

        assertThat(preview.reassignedHexCount()).isEqualTo(1);
        assertThat(preview.looseTasks()).hasSize(1);
        assertThat(preview.looseTasks().get(0).toDaId()).isEqualTo(MEENA);
        assertThat(preview.custodyTasks()).hasSize(1);
        assertThat(preview.custodyTasks().get(0).toDaId()).isEqualTo(MEENA);
        assertThat(preview.orphanTasks()).hasSize(1);
        // Staged as PENDING for the manager / auto-apply.
        ArgumentCaptor<DaAbsenceEvent> saved = ArgumentCaptor.forClass(DaAbsenceEvent.class);
        verify(absenceRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AbsenceStatus.PENDING);
    }

    @Test
    void applyMovesLooseTaskToNewOwnerAsQueuedNeverDeferred() {
        stubShiftScope();
        DaAbsenceEvent event = pendingEvent();
        when(absenceRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(gridService.applyAbsenceReassignment(eq(CITY), eq(List.of(RAVI)), any(), any(), any()))
                .thenReturn(planWithTileAToMeena());
        DispatchQueue loose = row(TILE_A, TaskStatus.QUEUED, TaskType.DELIVERY);
        when(queueRepository.findByDaIdAndOperatingDateAndStatusIn(eq(RAVI), any(), any()))
                .thenReturn(List.of(loose));
        when(queueRepository.findByDaIdAndOperatingDateAndStatusIn(eq(MEENA), any(), any()))
                .thenReturn(List.of());
        runLockInline();

        AbsenceApplyResponse res = service.apply(event.getId(), UUID.randomUUID(), null);

        assertThat(res.movedTaskCount()).isEqualTo(1);
        // A new QUEUED row for the new owner + the old row cancelled — no DEFERRED anywhere.
        ArgumentCaptor<DispatchQueue> saved = ArgumentCaptor.forClass(DispatchQueue.class);
        verify(queueRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(r -> {
            assertThat(r.getDaId()).isEqualTo(MEENA);
            assertThat(r.getTaskType()).isEqualTo(TaskType.DELIVERY);
            assertThat(r.getStatus()).isEqualTo(TaskStatus.QUEUED);
        });
        assertThat(loose.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(saved.getAllValues()).noneMatch(r -> r.getStatus() == TaskStatus.DEFERRED);
        verify(reorderService).reorder(eq(MEENA), any());
    }

    @Test
    void applyCreatesCustodyCollectForInProgressParcel() {
        stubShiftScope();
        DaAbsenceEvent event = pendingEvent();
        when(absenceRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(gridService.applyAbsenceReassignment(eq(CITY), eq(List.of(RAVI)), any(), any(), any()))
                .thenReturn(planWithTileAToMeena());
        when(queueRepository.findByDaIdAndOperatingDateAndStatusIn(eq(RAVI), any(), any()))
                .thenReturn(List.of(row(TILE_A, TaskStatus.IN_PROGRESS, TaskType.DELIVERY)));
        when(queueRepository.findByDaIdAndOperatingDateAndStatusIn(eq(MEENA), any(), any()))
                .thenReturn(List.of());
        runLockInline();

        AbsenceApplyResponse res = service.apply(event.getId(), UUID.randomUUID(), null);

        assertThat(res.custodyTaskCount()).isEqualTo(1);
        ArgumentCaptor<DispatchQueue> saved = ArgumentCaptor.forClass(DispatchQueue.class);
        verify(queueRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(r -> {
            assertThat(r.getDaId()).isEqualTo(MEENA);
            assertThat(r.getTaskType()).isEqualTo(TaskType.CUSTODY_COLLECT);
            assertThat(r.getCollectFromDaId()).isEqualTo(RAVI);
        });
    }

    @Test
    void applyTakesAbsentDaOfflineAndMarksApplied() {
        stubShiftScope();
        DaAbsenceEvent event = pendingEvent();
        when(absenceRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(gridService.applyAbsenceReassignment(any(), any(), any(), any(), any()))
                .thenReturn(planWithTileAToMeena());
        when(queueRepository.findByDaIdAndOperatingDateAndStatusIn(any(), any(), any()))
                .thenReturn(List.of());
        when(gridService.getActiveAssignments(any(), any())).thenReturn(List.of());

        AbsenceApplyResponse res = service.apply(event.getId(), UUID.randomUUID(), null);

        verify(daStatusService).updateStatus(RAVI, DaStatusEnum.ABSENT);
        verify(daStatusService).setTerritory(eq(RAVI), eq(List.of()));
        assertThat(res.status()).isEqualTo(AbsenceStatus.APPLIED);
        assertThat(event.getStatus()).isEqualTo(AbsenceStatus.APPLIED);
    }

    @Test
    void autoApplyMarksAutoApplied() {
        stubShiftScope();
        DaAbsenceEvent event = pendingEvent();
        when(absenceRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(gridService.applyAbsenceReassignment(any(), any(), any(), any(), any()))
                .thenReturn(planWithTileAToMeena());
        when(queueRepository.findByDaIdAndOperatingDateAndStatusIn(any(), any(), any()))
                .thenReturn(List.of());
        when(gridService.getActiveAssignments(any(), any())).thenReturn(List.of());

        AbsenceApplyResponse res = service.autoApply(event.getId());

        assertThat(res.status()).isEqualTo(AbsenceStatus.AUTO_APPLIED);
    }

    @Test
    void applyRejectsAnEventOutsideTheCallersCity() {
        DaAbsenceEvent event = pendingEvent();   // cityId == CITY
        when(absenceRepository.findById(event.getId())).thenReturn(Optional.of(event));
        UUID otherCity = UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.apply(event.getId(), UUID.randomUUID(), otherCity))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(gridService, never()).applyAbsenceReassignment(any(), any(), any(), any(), any());
    }

    @Test
    void applyIsIdempotentOnceApplied() {
        DaAbsenceEvent event = pendingEvent();
        event.setStatus(AbsenceStatus.APPLIED);
        when(absenceRepository.findById(event.getId())).thenReturn(Optional.of(event));

        AbsenceApplyResponse res = service.apply(event.getId(), UUID.randomUUID(), null);

        assertThat(res.status()).isEqualTo(AbsenceStatus.APPLIED);
        verify(gridService, never()).applyAbsenceReassignment(any(), any(), any(), any(), any());
    }

    @Test
    void previewRejectsADaThatIsNotOnThisCitysShift() {
        DaStatus foreign = new DaStatus();
        foreign.setDaId(RAVI);
        foreign.setCityId(UUID.randomUUID());   // a different city's roster
        foreign.setShiftDate(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        foreign.setShiftType("SHIFT_2");
        foreign.setStatus(DaStatusEnum.IDLE);
        when(daStatusRepository.findByDaId(RAVI)).thenReturn(Optional.of(foreign));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.preview(CITY, List.of(RAVI), "sick", UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(gridService, never()).planAbsenceReassignment(any(), any(), any(), any());
    }

    private DaAbsenceEvent pendingEvent() {
        DaAbsenceEvent event = new DaAbsenceEvent() {
            private final UUID id = UUID.randomUUID();
            @Override public UUID getId() { return id; }
        };
        event.setCityId(CITY);
        // IST, to match the service's today() (Asia/Kolkata) and the da_status shiftDate stub — otherwise
        // a UTC CI runner near IST-midnight lands a day earlier and the roster date-check rejects it.
        event.setOperatingDate(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        event.setAbsentDaIdList(List.of(RAVI));
        event.setStatus(AbsenceStatus.PENDING);
        return event;
    }

    private void stubShiftScope() {
        DaStatus st = new DaStatus();
        st.setDaId(RAVI);
        st.setCityId(CITY);
        st.setShiftDate(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));   // must match service today()
        st.setShiftType("SHIFT_2");
        st.setStatus(DaStatusEnum.IDLE);                                        // on the clock (preview path)
        when(daStatusRepository.findByDaId(RAVI)).thenReturn(Optional.of(st));
        when(daStatusRepository.findByCityIdAndShiftDateAndShiftType(eq(CITY), any(), eq("SHIFT_2")))
                .thenReturn(List.of(st));
    }

    @SuppressWarnings("unchecked")
    private void runLockInline() {
        when(daStatusService.withDaLock(any(), any()))
                .thenAnswer(i -> ((Supplier<Object>) i.getArgument(1)).get());
    }
}
