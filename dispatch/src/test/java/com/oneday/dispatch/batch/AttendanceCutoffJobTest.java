package com.oneday.dispatch.batch;

import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaAttendance;
import com.oneday.dispatch.domain.DaAttendanceMethod;
import com.oneday.dispatch.domain.DaAttendanceStatus;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaAttendanceRepository;
import com.oneday.grid.service.GridService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceCutoffJobTest {

    @Mock GridService gridService;
    @Mock DaDirectoryPort daDirectory;
    @Mock DaAttendanceRepository attendanceRepository;
    @Mock DaEventProducer eventProducer;

    private DispatchProperties props;
    private AttendanceCutoffJob job;

    private final UUID cityId = UUID.randomUUID();
    private final UUID present = UUID.randomUUID();
    private final UUID missing = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 8, 27);

    @BeforeEach
    void setUp() {
        props = new DispatchProperties();
        props.getShift().setCities(List.of("delhi"));
        job = new AttendanceCutoffJob(gridService, daDirectory, attendanceRepository, eventProducer, props);
    }

    @Test
    void sweep_alertsOnlyUnconfirmedRosterDas() {
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(daDirectory.availableDaIds("delhi", date, Shift.SHIFT_1)).thenReturn(List.of(present, missing));
        when(attendanceRepository.findByCityIdAndAttendanceDateAndShiftType(cityId, date, "SHIFT_1"))
                .thenReturn(List.of(attendance(present)));

        job.sweep(date, Shift.SHIFT_1);

        // The present DA is settled → no alert; the missing DA → one alert.
        verify(eventProducer).emitAttendanceUnconfirmed(missing, cityId, date, Shift.SHIFT_1, "delhi");
        verify(eventProducer, never()).emitAttendanceUnconfirmed(eq(present), eq(cityId), eq(date),
                eq(Shift.SHIFT_1), eq("delhi"));
    }

    @Test
    void sweep_shift1_ignoresShift2Roster() {
        // The SHIFT_1 sweep only reconciles SHIFT_1's roster — a SHIFT_2 DA never enters this sweep.
        when(gridService.resolveCityId("delhi")).thenReturn(cityId);
        when(daDirectory.availableDaIds("delhi", date, Shift.SHIFT_1)).thenReturn(List.of());

        job.sweep(date, Shift.SHIFT_1);

        verify(daDirectory).availableDaIds("delhi", date, Shift.SHIFT_1);
        verify(eventProducer, never()).emitAttendanceUnconfirmed(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private DaAttendance attendance(UUID daId) {
        DaAttendance a = new DaAttendance();
        a.setDaId(daId);
        a.setCityId(cityId);
        a.setAttendanceDate(date);
        a.setShiftType("SHIFT_1");
        a.setStatus(DaAttendanceStatus.PRESENT);
        a.setMethod(DaAttendanceMethod.AUTO_GEOFENCE);
        return a;
    }
}
