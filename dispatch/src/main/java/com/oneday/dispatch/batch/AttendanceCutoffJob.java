package com.oneday.dispatch.batch;

import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaAttendance;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaAttendanceRepository;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fires {@code cutoffOffsetMinutes} before each shift start (05:30 SHIFT_1 / 13:30 SHIFT_2, IST). For
 * every rostered DA of the starting shift whose hub proximity was not confirmed (no attendance row),
 * emits {@code ATTENDANCE_UNCONFIRMED} so M11 opens an alert for the station manager. Unlike
 * {@link AbsentDaDetectionJob} (which skips never-online DAs), this reconciles against the contracted
 * roster, so a DA who never came online is exactly the case it catches.
 */
@Component
public class AttendanceCutoffJob {

    private static final Logger log = LoggerFactory.getLogger(AttendanceCutoffJob.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final GridService gridService;
    private final DaDirectoryPort daDirectory;
    private final DaAttendanceRepository attendanceRepository;
    private final DaEventProducer eventProducer;
    private final DispatchProperties props;

    public AttendanceCutoffJob(GridService gridService, DaDirectoryPort daDirectory,
                               DaAttendanceRepository attendanceRepository, DaEventProducer eventProducer,
                               DispatchProperties props) {
        this.gridService = gridService;
        this.daDirectory = daDirectory;
        this.attendanceRepository = attendanceRepository;
        this.eventProducer = eventProducer;
        this.props = props;
    }

    @Scheduled(cron = "${dispatch.attendance.cutoff-cron:0 30 5,13 * * *}",
            zone = "${dispatch.attendance.zone:Asia/Kolkata}")
    public void onSchedule() {
        sweep(LocalDate.now(IST), currentCutoffShift());
    }

    /** Which shift's cutoff the current fire time is (before noon = SHIFT_1, else SHIFT_2). */
    static Shift currentCutoffShift() {
        return LocalTime.now(IST).getHour() < 12 ? Shift.SHIFT_1 : Shift.SHIFT_2;
    }

    /** Sweep every configured city for {@code date} + {@code shift}. Package-visible for fixed-clock tests. */
    public void sweep(LocalDate date, Shift shift) {
        for (String cityCode : props.getShift().getCities()) {
            try {
                sweepCity(cityCode, date, shift);
            } catch (RuntimeException e) {
                // One city failing must not starve the others.
                log.error("Attendance cutoff failed for city {} shift {} on {}", cityCode, shift, date, e);
            }
        }
    }

    private void sweepCity(String cityCode, LocalDate date, Shift shift) {
        UUID cityId = gridService.resolveCityId(cityCode);
        List<UUID> expected = daDirectory.availableDaIds(cityCode, date, shift);
        if (expected.isEmpty()) {
            return;
        }
        // A DA with ANY attendance row for the day is settled (present, or manager-marked) — no alert.
        Set<UUID> settled = new HashSet<>();
        for (DaAttendance r : attendanceRepository
                .findByCityIdAndAttendanceDateAndShiftType(cityId, date, shift.name())) {
            settled.add(r.getDaId());
        }
        int alerts = 0;
        for (UUID daId : expected) {
            if (!settled.contains(daId)) {
                eventProducer.emitAttendanceUnconfirmed(daId, cityId, date, shift, cityCode);
                alerts++;
            }
        }
        log.info("Attendance cutoff {} {} {}: {} rostered, {} unconfirmed",
                cityCode, shift, date, expected.size(), alerts);
    }
}
