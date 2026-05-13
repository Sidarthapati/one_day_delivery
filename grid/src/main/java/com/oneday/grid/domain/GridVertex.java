package com.oneday.grid.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

// Pre-computed at grid init. A grid with M×N tiles has (M+1)×(N+1) vertices.
@Entity
@Table(name = "grid_vertex")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GridVertex {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "grid_id", nullable = false, updatable = false)
    private UUID gridId;

    @Column(name = "row_idx", nullable = false, updatable = false)
    private int rowIdx;

    @Column(name = "col_idx", nullable = false, updatable = false)
    private int colIdx;

    @Column(name = "lat", nullable = false, updatable = false)
    private double lat;

    @Column(name = "lon", nullable = false, updatable = false)
    private double lon;
}
