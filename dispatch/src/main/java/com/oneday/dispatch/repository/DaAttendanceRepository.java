package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DaAttendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DaAttendanceRepository extends JpaRepository<DaAttendance, UUID> {

    /** The DA's attendance row for a day, if any (one per DA/day via the unique constraint). */
    Optional<DaAttendance> findByDaIdAndAttendanceDate(UUID daId, LocalDate attendanceDate);

    /** A city's attendance rows for a shift/date — the muster read. */
    List<DaAttendance> findByCityIdAndAttendanceDateAndShiftType(
            UUID cityId, LocalDate attendanceDate, String shiftType);
}
