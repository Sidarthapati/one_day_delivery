package com.oneday.dispatch.service.impl;

import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaAttendance;
import com.oneday.dispatch.domain.DaAttendanceMethod;
import com.oneday.dispatch.domain.DaAttendanceStatus;
import com.oneday.dispatch.domain.DaGpsPing;
import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.dto.response.AttendanceMusterEntry;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaAttendanceRepository;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.service.AbsenceReassignmentService;
import com.oneday.grid.service.GridService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceImplTest {

    // DEL hub coordinate used by the geofence config below.
    private static final double HUB_LAT = 28.5000;
    private static final double HUB_LON = 77.1500;

    @Mock DaAttendanceRepository attendanceRepository;
    @Mock DaStatusRepository daStatusRepository;
    @Mock DaGpsPingRepository daGpsPingRepository;
    @Mock DaDirectoryPort daDirectory;
    @Mock AbsenceReassignmentService absenceService;
    @Mock DaEventProducer eventProducer;
    @Mock GridService gridService;

    private DispatchProperties props;
    private AttendanceServiceImpl service;

    private final UUID daId = UUID.randomUUID();
    private final UUID cityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = new DispatchProperties();
        props.getAttendance().setRadiusMeters(500);
        DispatchProperties.Attendance.HubLocation hub = new DispatchProperties.Attendance.HubLocation();
        hub.setLat(HUB_LAT);
        hub.setLon(HUB_LON);
        props.getAttendance().getHubLocations().put(cityId.toString(), hub);

        service = new AttendanceServiceImpl(attendanceRepository, daStatusRepository, daGpsPingRepository,
                daDirectory, absenceService, eventProducer, gridService, props);
    }

    @Test
    void onGpsFix_withinRadius_marksPresentOnce() {
        when(attendanceRepository.findByDaIdAndAttendanceDate(eq(daId), any())).thenReturn(Optional.empty());

        // First ping at the hub → present.
        service.onGpsFix(daId, cityId, "SHIFT_1", HUB_LAT, HUB_LON, Instant.now());
        // Second ping → in-memory guard short-circuits, no further DB work.
        service.onGpsFix(daId, cityId, "SHIFT_1", HUB_LAT, HUB_LON, Instant.now());

        ArgumentCaptor<DaAttendance> saved = ArgumentCaptor.forClass(DaAttendance.class);
        verify(attendanceRepository, times(1)).save(saved.capture());
        verify(attendanceRepository, times(1)).findByDaIdAndAttendanceDate(eq(daId), any());
        assertThat(saved.getValue().getStatus()).isEqualTo(DaAttendanceStatus.PRESENT);
        assertThat(saved.getValue().getMethod()).isEqualTo(DaAttendanceMethod.AUTO_GEOFENCE);
        assertThat(saved.getValue().getDistanceM()).isLessThan(1.0);
    }

    @Test
    void onGpsFix_outsideRadius_doesNothing() {
        // ~1.5 km south of the hub — well outside 500 m.
        service.onGpsFix(daId, cityId, "SHIFT_1", HUB_LAT - 0.0135, HUB_LON, Instant.now());
        verify(attendanceRepository, never()).save(any());
        verify(attendanceRepository, never()).findByDaIdAndAttendanceDate(any(), any());
    }

    @Test
    void checkIn_farFromHub_returns422() {
        DaStatus da = new DaStatus();
        da.setDaId(daId);
        da.setCityId(cityId);
        da.setShiftType("SHIFT_1");
        when(daStatusRepository.findByDaId(daId)).thenReturn(Optional.of(da));

        assertThatThrownBy(() -> service.checkIn(daId, HUB_LAT - 0.02, HUB_LON))
                .hasMessageContaining("from the hub");
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void checkIn_atHub_marksPresentManual() {
        DaStatus da = new DaStatus();
        da.setDaId(daId);
        da.setCityId(cityId);
        da.setShiftType("SHIFT_1");
        when(daStatusRepository.findByDaId(daId)).thenReturn(Optional.of(da));
        when(attendanceRepository.findByDaIdAndAttendanceDate(eq(daId), any())).thenReturn(Optional.empty());

        service.checkIn(daId, HUB_LAT, HUB_LON);

        ArgumentCaptor<DaAttendance> saved = ArgumentCaptor.forClass(DaAttendance.class);
        verify(attendanceRepository).save(saved.capture());
        assertThat(saved.getValue().getMethod()).isEqualTo(DaAttendanceMethod.MANUAL_CHECKIN);
    }

    @Test
    void checkIn_alreadySettled_isIdempotent_noGeofenceRecheck() {
        // A DA already present (e.g. auto-marked at the hub earlier) who has since walked away and taps
        // again: returns the existing record, no 422, no second save, no da_status lookup.
        DaAttendance present = new DaAttendance();
        present.setDaId(daId);
        present.setCityId(cityId);
        present.setStatus(DaAttendanceStatus.PRESENT);
        present.setMethod(DaAttendanceMethod.AUTO_GEOFENCE);
        when(attendanceRepository.findByDaIdAndAttendanceDate(eq(daId), any()))
                .thenReturn(Optional.of(present));

        AttendanceMusterEntry entry = service.checkIn(daId, HUB_LAT - 0.02, HUB_LON); // far from hub

        assertThat(entry.status()).isEqualTo("PRESENT");
        verify(attendanceRepository, never()).save(any());
        verify(daStatusRepository, never()).findByDaId(any());
    }

    @Test
    void today_present_returnsPresentEntry() {
        DaAttendance present = new DaAttendance();
        present.setDaId(daId);
        present.setStatus(DaAttendanceStatus.PRESENT);
        present.setMethod(DaAttendanceMethod.MANUAL_CHECKIN);
        when(attendanceRepository.findByDaIdAndAttendanceDate(eq(daId), any()))
                .thenReturn(Optional.of(present));

        AttendanceMusterEntry entry = service.today(daId);

        assertThat(entry.status()).isEqualTo("PRESENT");
        assertThat(entry.method()).isEqualTo("MANUAL_CHECKIN");
    }

    @Test
    void today_noRow_returnsPending() {
        when(attendanceRepository.findByDaIdAndAttendanceDate(eq(daId), any())).thenReturn(Optional.empty());

        AttendanceMusterEntry entry = service.today(daId);

        assertThat(entry.status()).isEqualTo("PENDING");
        assertThat(entry.method()).isNull();
    }

    @Test
    void checkIn_concurrentInsert_returnsWinnerNotError() {
        DaStatus da = new DaStatus();
        da.setDaId(daId);
        da.setCityId(cityId);
        da.setShiftType("SHIFT_1");
        when(daStatusRepository.findByDaId(daId)).thenReturn(Optional.of(da));
        DaAttendance winner = new DaAttendance();
        winner.setDaId(daId);
        winner.setStatus(DaAttendanceStatus.PRESENT);
        winner.setMethod(DaAttendanceMethod.AUTO_GEOFENCE);
        // First lookup (settled check) empty; the save loses the unique race; reload returns the winner.
        when(attendanceRepository.findByDaIdAndAttendanceDate(eq(daId), any()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(attendanceRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        AttendanceMusterEntry entry = service.checkIn(daId, HUB_LAT, HUB_LON);

        assertThat(entry.status()).isEqualTo("PRESENT");
        assertThat(entry.method()).isEqualTo("AUTO_GEOFENCE");
    }

    @Test
    void markAbsent_futureDate_rejected() {
        assertThatThrownBy(() -> service.markAbsent(daId, LocalDate.now().plusDays(2), "x",
                UUID.randomUUID(), cityId))
                .hasMessageContaining("future date");
        verify(attendanceRepository, never()).save(any());
        verify(absenceService, never()).preview(any(), any(), any(), any());
    }

    @Test
    void markAbsent_recordsAbsent_triggersReassignment_andEmitsResolved() {
        DaStatus da = new DaStatus();
        da.setDaId(daId);
        da.setCityId(cityId);
        da.setShiftType("SHIFT_1");
        when(daStatusRepository.findByDaId(daId)).thenReturn(Optional.of(da));
        when(attendanceRepository.findByDaIdAndAttendanceDate(eq(daId), any())).thenReturn(Optional.empty());
        LocalDate date = LocalDate.of(2026, 8, 27);
        UUID actor = UUID.randomUUID();

        service.markAbsent(daId, date, "no-show", actor, cityId);

        ArgumentCaptor<DaAttendance> saved = ArgumentCaptor.forClass(DaAttendance.class);
        verify(attendanceRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DaAttendanceStatus.ABSENT);
        assertThat(saved.getValue().getMethod()).isEqualTo(DaAttendanceMethod.MANAGER_ABSENT);
        verify(absenceService).preview(eq(cityId), eq(java.util.List.of(daId)), eq("no-show"), eq(actor));
        verify(eventProducer).emitAttendanceResolved(daId, cityId, date, false);
    }

    @Test
    void markAbsent_outsideManagerCity_forbidden() {
        DaStatus da = new DaStatus();
        da.setDaId(daId);
        da.setCityId(cityId);
        when(daStatusRepository.findByDaId(daId)).thenReturn(Optional.of(da));

        assertThatThrownBy(() -> service.markAbsent(daId, LocalDate.of(2026, 8, 27), "x",
                UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("outside your city");
        verify(absenceService, never()).preview(any(), any(), any(), any());
    }

    @Test
    void markPresent_recordsPresent_andEmitsResolved() {
        DaStatus da = new DaStatus();
        da.setDaId(daId);
        da.setCityId(cityId);
        da.setShiftType("SHIFT_1");
        when(daStatusRepository.findByDaId(daId)).thenReturn(Optional.of(da));
        when(attendanceRepository.findByDaIdAndAttendanceDate(eq(daId), any())).thenReturn(Optional.empty());
        LocalDate date = LocalDate.of(2026, 8, 27);

        service.markPresent(daId, date, UUID.randomUUID(), cityId);

        ArgumentCaptor<DaAttendance> saved = ArgumentCaptor.forClass(DaAttendance.class);
        verify(attendanceRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DaAttendanceStatus.PRESENT);
        assertThat(saved.getValue().getMethod()).isEqualTo(DaAttendanceMethod.MANAGER_PRESENT);
        verify(eventProducer).emitAttendanceResolved(daId, cityId, date, true);
    }
}
