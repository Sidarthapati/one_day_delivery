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
@Table(name = "h3_hex")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Hex {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "h3_grid_id", nullable = false, updatable = false)
    private UUID h3GridId;

    @Column(name = "h3_index", nullable = false, updatable = false)
    private long h3Index;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "traversal_cap_sec")
    private Integer traversalCapSec;
}
