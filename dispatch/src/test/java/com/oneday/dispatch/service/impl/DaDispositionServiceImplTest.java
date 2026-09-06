package com.oneday.dispatch.service.impl;

import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaDisposition;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DispositionCategory;
import com.oneday.dispatch.domain.DispositionReason;
import com.oneday.dispatch.domain.DispositionStatus;
import com.oneday.dispatch.dto.request.DispositionRequest;
import com.oneday.dispatch.dto.response.DispositionResponse;
import com.oneday.dispatch.dto.response.DispositionSlotsResponse;
import com.oneday.dispatch.repository.DaDispositionRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.dispatch.service.model.DaQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DaDispositionServiceImplTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final UUID DA = UUID.randomUUID();
    private static final UUID CITY = UUID.randomUUID();

    @Mock private DaDispositionRepository repository;
    @Mock private DaStatusService daStatusService;
    @Mock private DaDirectoryPort daDirectory;

    private DaDispositionServiceImpl service;

    // A SHIFT_1 day (06:00–14:00 IST) with a single cron/hub-return meeting at 13:00 → protected 12:30–13:00.
    private final LocalDate day = LocalDate.of(2026, 9, 8);
    private Instant at(int hour, int minute) {
        return day.atTime(hour, minute).atZone(IST).toInstant();
    }

    @BeforeEach
    void setUp() {
        DispatchProperties props = new DispatchProperties();   // defaults: allowance 60, minBreak 30, freeze 30, escalateAfter 30
        service = new DaDispositionServiceImpl(repository, daStatusService, daDirectory, props);
        service.setClock(Clock.fixed(at(9, 0), IST));           // "now" = 09:00 IST, mid-shift
        // In-memory live state + queue with a 13:00 meeting.
        DaLiveStatus live = new DaLiveStatus();
        live.setDaId(DA);
        live.setCityId(CITY);
        live.setStatus(DaStatusEnum.IDLE);
        live.setShiftType("SHIFT_1");
        lenient().when(daStatusService.getLiveStatus(DA)).thenReturn(live);
        lenient().when(daStatusService.getStatus(DA)).thenReturn(DaStatusEnum.IDLE);
        DaCronAssignment cron = new DaCronAssignment();
        cron.setMeetingTimes(new ArrayList<>(List.of("13:00")));
        DaQueue queue = new DaQueue(DA, cron);
        lenient().when(daStatusService.getQueue(DA)).thenReturn(queue);
        lenient().when(daStatusService.withDaLock(any(), any()))
                .thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(repository.findFirstByDaIdAndStatusIn(any(), any())).thenReturn(Optional.empty());
        lenient().when(repository.findByDaIdAndOperatingDate(any(), any())).thenReturn(List.of());
    }

    @Test
    void slots_excludeTheProtectedPreCronWindow() {
        DispositionSlotsResponse resp = service.slots(DA);
        // From 09:00 to shift end 14:00, minus 12:30–13:00 → windows [09:00,12:30] and [13:00,14:00].
        assertThat(resp.slots()).hasSize(2);
        assertThat(resp.slots().get(0).start()).isEqualTo(at(9, 0));
        assertThat(resp.slots().get(0).end()).isEqualTo(at(12, 30));
        assertThat(resp.slots().get(1).start()).isEqualTo(at(13, 0));
        assertThat(resp.slots().get(1).end()).isEqualTo(at(14, 0));
        assertThat(resp.remainingAllowanceMinutes()).isEqualTo(60);
    }

    @Test
    void breakInAnAllowedSlot_autoApprovesAndHoldsTerritory() {
        DispositionResponse resp = service.request(DA,
                new DispositionRequest(DispositionCategory.BREAK, DispositionReason.LUNCH, 30, null), DA);
        assertThat(resp.status()).isEqualTo(DispositionStatus.ACTIVE);
        assertThat(resp.scheduledEnd()).isEqualTo(at(9, 30));
        verify(daStatusService).updateStatus(DA, DaStatusEnum.ON_BREAK);
    }

    @Test
    void breakThatWouldOverrunIntoTheProtectedWindow_isRefused() {
        service.setClock(Clock.fixed(at(12, 15), IST));   // 12:15 + 30 = 12:45, inside 12:30–13:00 protected
        assertThatThrownBy(() -> service.request(DA,
                new DispositionRequest(DispositionCategory.BREAK, DispositionReason.LUNCH, 30, null), DA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not fit an allowed slot");
        verify(daStatusService, never()).updateStatus(any(), any());
    }

    @Test
    void breakExceedingRemainingAllowance_isRefused() {
        DaDisposition used = breakRow(DispositionStatus.COMPLETED, 45);
        when(repository.findByDaIdAndOperatingDate(any(), any())).thenReturn(List.of(used));  // 15 left
        assertThatThrownBy(() -> service.request(DA,
                new DispositionRequest(DispositionCategory.BREAK, DispositionReason.REST, 30, null), DA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("daily allowance");
    }

    @Test
    void secondConcurrentRequest_isRefused() {
        when(repository.findFirstByDaIdAndStatusIn(any(), any()))
                .thenReturn(Optional.of(breakRow(DispositionStatus.ACTIVE, 30)));
        assertThatThrownBy(() -> service.request(DA,
                new DispositionRequest(DispositionCategory.BREAK, DispositionReason.LUNCH, 30, null), DA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active or pending");
    }

    @Test
    void auxiliaryIsLeftPendingAndDoesNotHoldTerritory() {
        DispositionResponse resp = service.request(DA,
                new DispositionRequest(DispositionCategory.AUXILIARY, DispositionReason.COMPANY_WORK, 45, "warehouse"), DA);
        assertThat(resp.status()).isEqualTo(DispositionStatus.PENDING);
        verify(daStatusService, never()).updateStatus(any(), any());
    }

    @Test
    void approveAuxiliary_activatesAndHoldsTerritory() {
        DaDisposition aux = new DaDisposition();
        aux.setDaId(DA);
        aux.setCityId(CITY);
        aux.setOperatingDate(day);
        aux.setCategory(DispositionCategory.AUXILIARY);
        aux.setReason(DispositionReason.COMPANY_WORK);
        aux.setStatus(DispositionStatus.PENDING);
        aux.setDurationMinutes(45);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(aux));
        DispositionResponse resp = service.approve(id, UUID.randomUUID(), CITY);
        assertThat(resp.status()).isEqualTo(DispositionStatus.ACTIVE);
        assertThat(resp.scheduledEnd()).isEqualTo(at(9, 45));
        verify(daStatusService).updateStatus(DA, DaStatusEnum.ON_BREAK);
    }

    @Test
    void endReturnsToWorkAndRefundsUnusedAllowance() {
        DaDisposition active = breakRow(DispositionStatus.ACTIVE, 30);
        active.setActualStart(at(9, 0));
        service.setClock(Clock.fixed(at(9, 10), IST));   // returned after 10 of 30 min
        when(repository.findFirstByDaIdAndStatusIn(any(), any())).thenReturn(Optional.of(active));
        when(daStatusService.getStatus(DA)).thenReturn(DaStatusEnum.ON_BREAK);
        DispositionResponse resp = service.end(DA);
        assertThat(resp.status()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(resp.durationMinutes()).isEqualTo(10);   // charged actual, not the reserved 30
        verify(daStatusService).updateStatus(DA, DaStatusEnum.IDLE);
    }

    @Test
    void sweepMarksOverstayedPastGraceAndBumpsEscalation() {
        DaDisposition active = breakRow(DispositionStatus.ACTIVE, 30);
        active.setActualStart(at(9, 0));
        active.setScheduledEnd(at(9, 30));
        when(repository.findByOperatingDateAndStatus(any(), any())).thenReturn(List.of(active));
        when(daStatusService.getStatus(DA)).thenReturn(DaStatusEnum.ON_BREAK);
        service.sweep(at(10, 5));   // 35 min past end ≥ 30 grace
        assertThat(active.getStatus()).isEqualTo(DispositionStatus.OVERSTAYED);
        assertThat(active.getEscalationLevel()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sweepClosesABreakTheDaHasLeft() {
        DaDisposition active = breakRow(DispositionStatus.ACTIVE, 30);
        active.setActualStart(at(9, 0));
        active.setScheduledEnd(at(9, 30));
        when(repository.findByOperatingDateAndStatus(any(), any())).thenReturn(List.of(active));
        when(daStatusService.getStatus(DA)).thenReturn(DaStatusEnum.CRON_LOCKED);   // cron took over
        service.sweep(at(9, 15));
        assertThat(active.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
    }

    private DaDisposition breakRow(DispositionStatus status, int minutes) {
        DaDisposition d = new DaDisposition();
        d.setDaId(DA);
        d.setCityId(CITY);
        d.setOperatingDate(day);
        d.setCategory(DispositionCategory.BREAK);
        d.setReason(DispositionReason.LUNCH);
        d.setStatus(status);
        d.setDurationMinutes(minutes);
        d.setCountsAllowance(true);
        return d;
    }
}
