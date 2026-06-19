package com.oneday.dispatch.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A DA's cron meeting (van rendezvous) for one operating day — the hard constraint M5 protects.
 * Backed by {@code da_cron_assignment}; sourced from M6's {@code DaCronScheduledEvent} /
 * {@code da_cron_schedule}. Status and completion fields are mutable.
 *
 * <p>NOTE (M6-D-008): M6 emits a LIST of meeting times per DA/day; v1 stores the next/primary
 * meeting here. Storing the full list is a follow-up when ShiftLoadJob lands (Phase 2).</p>
 */
@Entity
@Table(name = "da_cron_assignment")
@Getter
@Setter
@NoArgsConstructor
public class DaCronAssignment extends MutableBaseEntity {

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "operating_date", nullable = false, updatable = false)
    private LocalDate operatingDate;

    @Column(name = "cron_vertex_id", nullable = false, updatable = false)
    private UUID cronVertexId;

    @Column(name = "meeting_lat", nullable = false, updatable = false)
    private double meetingLat;

    @Column(name = "meeting_lon", nullable = false, updatable = false)
    private double meetingLon;

    @Column(name = "scheduled_meeting_time", nullable = false)
    private Instant scheduledMeetingTime;

    @Column(name = "van_id")
    private UUID vanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CronAssignmentStatus status;

    @Column(name = "handoff_completed_at")
    private Instant handoffCompletedAt;

    @Column(name = "parcel_count_handed")
    private Integer parcelCountHanded;
}
