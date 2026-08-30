package com.oneday.dispatch.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Global attendance config — a single row (the earliest by created_at). Currently just the
 * {@code autoPresentEnabled} master switch for geofence auto-present. Ops-tunable at runtime via the
 * admin toggle, unlike the static yaml knobs in {@code DispatchProperties.Attendance}.
 */
@Entity
@Table(name = "attendance_config")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceConfig extends MutableBaseEntity {

    @Column(name = "auto_present_enabled", nullable = false)
    private boolean autoPresentEnabled = true;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;
}
