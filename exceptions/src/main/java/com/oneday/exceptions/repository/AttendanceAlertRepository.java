package com.oneday.exceptions.repository;

import com.oneday.exceptions.domain.AttendanceAlert;
import com.oneday.exceptions.domain.AttendanceAlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceAlertRepository extends JpaRepository<AttendanceAlert, UUID> {

    /** The alert for a DA on a day, if any (one per DA/date via the unique constraint). */
    Optional<AttendanceAlert> findByDaIdAndAttendanceDate(UUID daId, LocalDate attendanceDate);

    /** Open alerts for a date, newest first (ADMIN — all cities). */
    List<AttendanceAlert> findByStatusAndAttendanceDateOrderByCreatedAtDesc(
            AttendanceAlertStatus status, LocalDate attendanceDate);

    /** Open alerts for a date scoped to one city (by city code or city UUID string), newest first. */
    List<AttendanceAlert> findByStatusAndAttendanceDateAndCityCodeOrderByCreatedAtDesc(
            AttendanceAlertStatus status, LocalDate attendanceDate, String cityCode);
}
