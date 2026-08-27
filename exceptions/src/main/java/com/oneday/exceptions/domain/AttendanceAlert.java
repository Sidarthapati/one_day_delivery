package com.oneday.exceptions.domain;

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
 * A station-manager inbox item raised by M5 when a rostered DA's hub proximity could not be confirmed
 * by the shift cutoff. DA + date scoped (not shipment-bound like {@link ExceptionCase}). Settled from
 * the station console: mark present, or mark absent (which triggers the M5 reassignment).
 */
@Entity
@Table(name = "attendance_alert")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceAlert extends MutableBaseEntity {

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "city_code", updatable = false)
    private String cityCode;

    @Column(name = "attendance_date", nullable = false, updatable = false)
    private LocalDate attendanceDate;

    @Column(name = "shift_type", updatable = false)
    private String shiftType;

    @Column(name = "da_name")
    private String daName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AttendanceAlertStatus status = AttendanceAlertStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution")
    private AttendanceResolution resolution;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;
}
