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
 * A DA's visit to one physical location on a shift — a "ticket" grouping the tasks the DA works at that
 * doorstep (its {@code dispatch_queue} rows back-reference it via {@code stub_id}). Backed by
 * {@code da_location_stub}.
 *
 * <p>The front-end already groups a DA's tasks by rounded coordinates ({@code groupTasksByLocation}); this
 * is the persisted, queryable counterpart, added so we can measure <b>dwell</b> — how long a DA spends at a
 * location — as {@code dwellSeconds} (first "Mark arrived" → last task completion in the visit).</p>
 *
 * <p>A stub is OPEN while the DA still has work there and CLOSED once every task is terminal. Only an OPEN
 * stub is ever joined (partial-unique on {@code status='OPEN'}), so a later return to the same location
 * opens a fresh visit rather than merging into the earlier one.</p>
 */
@Entity
@Table(name = "da_location_stub")
@Getter
@Setter
@NoArgsConstructor
public class DaLocationStub extends MutableBaseEntity {

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "operating_date", nullable = false, updatable = false)
    private LocalDate operatingDate;

    /** "lat,lon" rounded to 5dp (~1.1m) — the grouping key, matching the DA app's {@code groupTasksByLocation}. */
    @Column(name = "location_key", nullable = false, updatable = false, length = 40)
    private String locationKey;

    @Column(name = "stub_lat", nullable = false, updatable = false)
    private double stubLat;

    @Column(name = "stub_lon", nullable = false, updatable = false)
    private double stubLon;

    /** The H3 tile the location falls in — a coarser grouping key for later hex-level rollups. */
    @Column(name = "tile_id")
    private UUID tileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private StubStatus status = StubStatus.OPEN;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Total tasks/shipments in the visit (the ticket size). */
    @Column(name = "task_count", nullable = false)
    private int taskCount;

    /** Tasks that completed successfully. */
    @Column(name = "processed_count", nullable = false)
    private int processedCount;

    /** Tasks that failed. */
    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    /** Earliest "Mark arrived" across the visit's tasks — the dwell clock start. */
    @Column(name = "first_arrived_at")
    private Instant firstArrivedAt;

    /** Latest task completion in the visit — the dwell clock end. */
    @Column(name = "last_completed_at")
    private Instant lastCompletedAt;

    /** Time-at-location in seconds ({@code lastCompletedAt - firstArrivedAt}). Null until both ends exist. */
    @Column(name = "dwell_seconds")
    private Long dwellSeconds;
}
