package com.oneday.routing.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Append-only. Per DA: vertex + the day's meeting times → DA_CRON_SCHEDULED to M5 (M6-D-008).
// meetingTimes is a JSON array of "HH:mm" strings; the service layer (de)serializes with Jackson.
@Entity
@Table(name = "da_cron_schedule")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DaCronSchedule {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "route_plan_id", nullable = false, updatable = false)
    private UUID routePlanId;

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "hex_vertex_id", nullable = false, updatable = false)
    private UUID hexVertexId;

    @Column(name = "van_id", updatable = false)
    private UUID vanId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meeting_times", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String meetingTimes;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "valid_date", nullable = false, updatable = false)
    private LocalDate validDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
