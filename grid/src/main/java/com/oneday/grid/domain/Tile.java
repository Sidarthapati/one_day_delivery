package com.oneday.grid.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "tile")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tile {

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

    // Named without 'is' prefix so Lombok generates isActive() correctly
    // without Hibernate property-access confusion. Column mapped explicitly.
    @Column(name = "is_active", nullable = false)
    private boolean active;

    // OSRM SW-corner → NE-corner road time; winsorisation cap for inter-stop travel.
    // Null until the OSRM matrix refresh runs for this tile's city.
    @Column(name = "traversal_cap_sec")
    private Integer traversalCapSec;
}
