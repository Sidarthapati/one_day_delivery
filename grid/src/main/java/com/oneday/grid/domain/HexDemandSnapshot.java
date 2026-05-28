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

// One row per hex per day. Append-only — never updated after write.
// demand_score_minutes is what the CP-SAT solver consumes.
@Entity
@Table(name = "h3_hex_demand_snapshot")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HexDemandSnapshot {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "hex_id", nullable = false, updatable = false)
    private UUID hexId;

    @Column(name = "snapshot_date", nullable = false, updatable = false)
    private LocalDate snapshotDate;

    @Column(name = "hist_avg_orders", nullable = false)
    private double histAvgOrders;

    @Column(name = "current_orders", nullable = false)
    private int currentOrders;

    @Column(name = "demand_score_orders", nullable = false)
    private double demandScoreOrders;

    @Column(name = "service_time_min", nullable = false)
    private double serviceTimeMin;

    @Column(name = "inter_stop_travel_min", nullable = false)
    private double interStopTravelMin;

    @Column(name = "order_engaged_min", nullable = false)
    private double orderEngagedMin;

    @Column(name = "demand_score_minutes", nullable = false)
    private double demandScoreMinutes;

    @Column(name = "is_bootstrapped", nullable = false)
    private boolean bootstrapped;
}
