package com.oneday.exceptions.service.impl;

import com.oneday.common.kafka.enums.DaEventType;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.exceptions.domain.AttendanceAlert;
import com.oneday.exceptions.domain.AttendanceAlertStatus;
import com.oneday.exceptions.domain.AttendanceResolution;
import com.oneday.exceptions.repository.AttendanceAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceAlertServiceImplTest {

    @Mock AttendanceAlertRepository repository;
    @Mock DaDirectoryPort daDirectory;

    private AttendanceAlertServiceImpl service;

    private final UUID daId = UUID.randomUUID();
    private final UUID cityId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 8, 27);

    @BeforeEach
    void setUp() {
        service = new AttendanceAlertServiceImpl(repository, daDirectory);
    }

    private DaLifecycleEvent event(DaEventType type, String reasonCode) {
        return new DaLifecycleEvent(UUID.randomUUID(), type, "1.0", Instant.now(),
                null, null, daId, cityId, null, null, reasonCode, null, date);
    }

    @Test
    void raise_opensAlertWithParsedShiftAndCity() {
        when(repository.findByDaIdAndAttendanceDate(daId, date)).thenReturn(Optional.empty());
        when(daDirectory.contactsFor(any())).thenReturn(Map.of(daId, new DaDirectoryPort.DaContact("Ravi", "9")));

        service.raise(event(DaEventType.ATTENDANCE_UNCONFIRMED, "SHIFT_1|delhi"));

        ArgumentCaptor<AttendanceAlert> saved = ArgumentCaptor.forClass(AttendanceAlert.class);
        verify(repository).save(saved.capture());
        AttendanceAlert a = saved.getValue();
        assertThat(a.getStatus()).isEqualTo(AttendanceAlertStatus.OPEN);
        assertThat(a.getShiftType()).isEqualTo("SHIFT_1");
        assertThat(a.getCityCode()).isEqualTo("delhi");
        assertThat(a.getDaName()).isEqualTo("Ravi");
    }

    @Test
    void raise_dedupesWhenAlreadyPresent() {
        when(repository.findByDaIdAndAttendanceDate(daId, date))
                .thenReturn(Optional.of(new AttendanceAlert()));

        service.raise(event(DaEventType.ATTENDANCE_UNCONFIRMED, "SHIFT_1|delhi"));

        verify(repository, never()).save(any());
    }

    @Test
    void resolve_closesOpenAlertAsAbsent() {
        AttendanceAlert open = new AttendanceAlert();
        open.setStatus(AttendanceAlertStatus.OPEN);
        when(repository.findByDaIdAndAttendanceDate(daId, date)).thenReturn(Optional.of(open));

        service.resolve(event(DaEventType.ATTENDANCE_RESOLVED, "ABSENT"));

        verify(repository).save(open);
        assertThat(open.getStatus()).isEqualTo(AttendanceAlertStatus.RESOLVED);
        assertThat(open.getResolution()).isEqualTo(AttendanceResolution.ABSENT);
        assertThat(open.getResolvedAt()).isNotNull();
    }

    @Test
    void resolve_beforeAnyAlert_isNoOp() {
        when(repository.findByDaIdAndAttendanceDate(daId, date)).thenReturn(Optional.empty());
        service.resolve(event(DaEventType.ATTENDANCE_RESOLVED, "PRESENT"));
        verify(repository, never()).save(any());
    }

    @Test
    void openAlerts_scopesByCityWhenGiven() {
        when(repository.findByStatusAndAttendanceDateAndCityCodeOrderByCreatedAtDesc(
                eq(AttendanceAlertStatus.OPEN), eq(date), eq("delhi"))).thenReturn(java.util.List.of());
        service.openAlerts("delhi", date);
        verify(repository).findByStatusAndAttendanceDateAndCityCodeOrderByCreatedAtDesc(
                AttendanceAlertStatus.OPEN, date, "delhi");
    }

    @Test
    void openAlerts_scopesByCityIdWhenScopeIsUuid() {
        String uuidScope = cityId.toString();
        when(repository.findByStatusAndAttendanceDateAndCityIdOrderByCreatedAtDesc(
                eq(AttendanceAlertStatus.OPEN), eq(date), eq(cityId))).thenReturn(java.util.List.of());
        service.openAlerts(uuidScope, date);
        verify(repository).findByStatusAndAttendanceDateAndCityIdOrderByCreatedAtDesc(
                AttendanceAlertStatus.OPEN, date, cityId);
        verify(repository, never()).findByStatusAndAttendanceDateAndCityCodeOrderByCreatedAtDesc(
                any(), any(), any());
    }

    @Test
    void openAlerts_allCitiesWhenScopeNull() {
        when(repository.findByStatusAndAttendanceDateOrderByCreatedAtDesc(
                eq(AttendanceAlertStatus.OPEN), eq(date))).thenReturn(java.util.List.of());
        service.openAlerts(null, date);
        verify(repository).findByStatusAndAttendanceDateOrderByCreatedAtDesc(AttendanceAlertStatus.OPEN, date);
    }
}
