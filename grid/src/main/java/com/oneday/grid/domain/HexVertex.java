package com.oneday.grid.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

// Pre-computed at grid init. H3 vertex indexes are globally unique.
@Entity
@Table(name = "h3_hex_vertex")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HexVertex {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "h3_grid_id", nullable = false, updatable = false)
    private UUID h3GridId;

    @Column(name = "h3_vertex_index", nullable = false, updatable = false)
    private long h3VertexIndex;

    @Column(name = "lat", nullable = false, updatable = false)
    private double lat;

    @Column(name = "lon", nullable = false, updatable = false)
    private double lon;
}
