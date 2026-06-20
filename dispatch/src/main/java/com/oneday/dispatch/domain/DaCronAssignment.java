package com.oneday.dispatch.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * The full day's meeting times (M6-D-008) as ISO LocalTime strings, e.g. {@code ["06:00","10:00"]}.
     * {@link #scheduledMeetingTime} holds the primary (earliest) for the current feasibility path.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meeting_times", nullable = false, columnDefinition = "jsonb")
    private List<String> meetingTimes = new ArrayList<>();

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
