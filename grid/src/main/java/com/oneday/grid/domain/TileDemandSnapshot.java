package com.oneday.grid.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.UUID;

// One row per tile per day. Append-only — never updated after write.
// demand_score_minutes is what the CP-SAT solver consumes.
// demand_score_orders is retained for station manager reporting and audit.
@Entity
@Table(name = "tile_demand_snapshot")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TileDemandSnapshot {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tile_id", nullable = false, updatable = false)
    private UUID tileId;

    @Column(name = "snapshot_date", nullable = false, updatable = false)
    private LocalDate snapshotDate;

    // 7-day rolling average order count
    @Column(name = "hist_avg_orders", nullable = false)
    private double histAvgOrders;

    // Orders placed today at snapshot time
    @Column(name = "current_orders", nullable = false)
    private int currentOrders;

    // 0.7 * histAvgOrders + 0.3 * currentOrders
    @Column(name = "demand_score_orders", nullable = false)
    private double demandScoreOrders;

    // Avg minutes at customer location per pickup (service component)
    @Column(name = "service_time_min", nullable = false)
    private double serviceTimeMin;

    // Avg minutes travelling between consecutive pickups within this tile
    @Column(name = "inter_stop_travel_min", nullable = false)
    private double interStopTravelMin;

    // serviceTimeMin + interStopTravelMin
    @Column(name = "order_engaged_min", nullable = false)
    private double orderEngagedMin;

    // demandScoreOrders * orderEngagedMin — passed to the CP-SAT solver
    @Column(name = "demand_score_minutes", nullable = false)
    private double demandScoreMinutes;

    // True if either demand component (service time or inter-stop travel) uses bootstrap defaults
    @Column(name = "is_bootstrapped", nullable = false)
    private boolean bootstrapped;
}
