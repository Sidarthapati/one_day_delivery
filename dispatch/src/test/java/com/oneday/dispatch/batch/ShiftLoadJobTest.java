package com.oneday.dispatch.batch;

import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.service.GridService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the shift-load orchestration; all collaborators are mocked. */
class ShiftLoadJobTest {

    private GridService gridService;
    private DaDirectoryPort daDirectoryPort;
    private DaCronAssignmentRepository cronRepo;
    private DispatchQueueRepository queueRepo;
    private com.oneday.dispatch.repository.DeferredDispatchRepository deferredRepo;
    private DaStatusService daStatusService;
    private ShiftLoadJob job;

    private final UUID cityId = UUID.randomUUID();
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        gridService = mock(GridService.class);
        daDirectoryPort = mock(DaDirectoryPort.class);
        cronRepo = mock(DaCronAssignmentRepository.class);
        queueRepo = mock(DispatchQueueRepository.class);
        deferredRepo = mock(com.oneday.dispatch.repository.DeferredDispatchRepository.class);
        daStatusService = mock(DaStatusService.class);

        DispatchProperties props = new DispatchProperties();
        props.getShift().setCities(List.of("bengaluru"));

        when(gridService.resolveCityId("bengaluru")).thenReturn(cityId);
        when(cronRepo.findByOperatingDateAndCityId(any(), any())).thenReturn(List.of());

        job = new ShiftLoadJob(gridService, daDirectoryPort, cronRepo, queueRepo, deferredRepo, daStatusService, props);
    }

    @Test
    void initShiftCalledOncePerDaInRoster() {
        UUID da1 = UUID.randomUUID();
        UUID da2 = UUID.randomUUID();
        // da1 owns two hexes, da2 owns one → roster is {da1, da2}.
        when(gridService.getActiveAssignments(cityId, today)).thenReturn(List.of(
                assignment(da1, UUID.randomUUID()),
                assignment(da1, UUID.randomUUID()),
                assignment(da2, UUID.randomUUID())));
        when(daDirectoryPort.availableDaIds("bengaluru", today, Shift.SHIFT_1)).thenReturn(List.of(da1, da2));
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(any(), any(), any())).thenReturn(List.of());

        job.loadShiftsForDate(today, Shift.SHIFT_1);

        verify(daStatusService).initShift(eq(da1), eq(cityId), eq(today), eq("SHIFT_1"), any());
        verify(daStatusService).initShift(eq(da2), eq(cityId), eq(today), eq("SHIFT_1"), any());
        verify(daStatusService, times(2)).initShift(any(), any(), any(), any(), any());
    }

    @Test
    void daNotOnThisShiftIsFilteredOut() {
        UUID onShift = UUID.randomUUID();
        UUID otherShift = UUID.randomUUID();
        when(gridService.getActiveAssignments(cityId, today)).thenReturn(List.of(
                assignment(onShift, UUID.randomUUID()),
                assignment(otherShift, UUID.randomUUID())));
        // Only onShift is on SHIFT_1's roster; otherShift belongs to SHIFT_2.
        when(daDirectoryPort.availableDaIds("bengaluru", today, Shift.SHIFT_1)).thenReturn(List.of(onShift));
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(any(), any(), any())).thenReturn(List.of());

        job.loadShiftsForDate(today, Shift.SHIFT_1);

        verify(daStatusService).initShift(eq(onShift), eq(cityId), eq(today), eq("SHIFT_1"), any());
        verify(daStatusService, times(1)).initShift(any(), any(), any(), any(), any());
    }

    @Test
    void staleInProgressEtaIsResetToNow() {
        UUID da = UUID.randomUUID();
        when(gridService.getActiveAssignments(cityId, today))
                .thenReturn(List.of(assignment(da, UUID.randomUUID())));
        when(daDirectoryPort.availableDaIds("bengaluru", today, Shift.SHIFT_1)).thenReturn(List.of(da));

        DispatchQueue stale = new DispatchQueue();
        stale.setExpectedEta(Instant.now().minusSeconds(3600));   // an hour overdue
        DispatchQueue future = new DispatchQueue();
        future.setExpectedEta(Instant.now().plusSeconds(3600));   // still ahead
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(da, today, List.of(TaskStatus.IN_PROGRESS)))
                .thenReturn(List.of(stale, future));

        Instant before = Instant.now();
        job.loadShiftsForDate(today, Shift.SHIFT_1);

        ArgumentCaptor<List<DispatchQueue>> saved = ArgumentCaptor.forClass(List.class);
        verify(queueRepo).saveAll(saved.capture());
        // Only the overdue row is rewritten, and its ETA is bumped to ~now.
        assertThat(saved.getValue()).containsExactly(stale);
        assertThat(stale.getExpectedEta()).isAfterOrEqualTo(before);
        assertThat(future.getExpectedEta()).isAfter(before.plusSeconds(1000));
    }

    @Test
    void cityFailureDoesNotAbortOtherCities() {
        UUID gda = UUID.randomUUID();
        DispatchProperties props = new DispatchProperties();
        props.getShift().setCities(List.of("bad", "bengaluru"));
        when(gridService.resolveCityId("bad")).thenThrow(new IllegalArgumentException("unknown city"));
        when(gridService.getActiveAssignments(cityId, today))
                .thenReturn(List.of(assignment(gda, UUID.randomUUID())));
        when(daDirectoryPort.availableDaIds("bengaluru", today, Shift.SHIFT_1)).thenReturn(List.of(gda));
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(any(), any(), any())).thenReturn(List.of());

        ShiftLoadJob twoCityJob =
                new ShiftLoadJob(gridService, daDirectoryPort, cronRepo, queueRepo, deferredRepo, daStatusService, props);
        twoCityJob.loadShiftsForDate(today, Shift.SHIFT_1);

        // The good city still loaded its single DA despite "bad" throwing.
        verify(daStatusService, times(1)).initShift(any(), eq(cityId), eq(today), any(), any());
    }

    @Test
    void noStaleEtasMeansNoSave() {
        UUID da = UUID.randomUUID();
        when(gridService.getActiveAssignments(cityId, today))
                .thenReturn(List.of(assignment(da, UUID.randomUUID())));
        when(daDirectoryPort.availableDaIds("bengaluru", today, Shift.SHIFT_1)).thenReturn(List.of(da));
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(any(), any(), any())).thenReturn(List.of());

        job.loadShiftsForDate(today, Shift.SHIFT_1);

        verify(queueRepo, never()).saveAll(any());
    }

    @Test
    void shiftLoadResetsPendingRetryBudget() {
        com.oneday.dispatch.domain.DeferredDispatch d = new com.oneday.dispatch.domain.DeferredDispatch();
        d.setRetryCount(2);
        d.setRetryAfter(Instant.now().plusSeconds(600));
        when(deferredRepo.findByCityIdAndStatus(cityId, "PENDING")).thenReturn(List.of(d));
        when(gridService.getActiveAssignments(cityId, today)).thenReturn(List.of());
        when(daDirectoryPort.availableDaIds("bengaluru", today, Shift.SHIFT_1)).thenReturn(List.of());

        job.loadShiftsForDate(today, Shift.SHIFT_1);

        // The incoming shift gets a clean retry budget so carry-overs aren't escalated prematurely.
        assertThat(d.getRetryCount()).isEqualTo(0);
        assertThat(d.getRetryAfter()).isNull();
        verify(deferredRepo).saveAll(List.of(d));
    }

    private AssignmentResponse assignment(UUID daId, UUID hexId) {
        return new AssignmentResponse(UUID.randomUUID(), UUID.randomUUID(), daId, hexId, today,
                1, AssignmentStatus.APPROVED, Instant.now(), null, null);
    }
}
