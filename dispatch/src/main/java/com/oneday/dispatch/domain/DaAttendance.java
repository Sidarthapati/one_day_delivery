package com.oneday.dispatch.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One positive attendance record per DA per day — the per-day ledger behind geocoded attendance.
 * Written when a GPS fix lands within the hub geofence (or the DA taps "I've arrived"), or when a
 * station manager overrides after the shift cutoff. Distinct from {@code da_status} (live snapshot).
 */
@Entity
@Table(name = "da_attendance")
@Getter
@Setter
@NoArgsConstructor
public class DaAttendance extends MutableBaseEntity {

    @Column(name = "da_id", nullable = false)
    private UUID daId;

    @Column(name = "city_id", nullable = false)
    private UUID cityId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "shift_type", length = 20)
    private String shiftType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DaAttendanceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 24)
    private DaAttendanceMethod method;

    @Column(name = "detected_lat")
    private Double detectedLat;

    @Column(name = "detected_lon")
    private Double detectedLon;

    @Column(name = "distance_m")
    private Double distanceM;

    @Column(name = "marked_by_user_id")
    private UUID markedByUserId;

    @Column(name = "source_ping_at")
    private Instant sourcePingAt;
}
