package com.oneday.grid.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "grid")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Grid extends BaseEntity {

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "origin_lat", nullable = false, updatable = false)
    private double originLat;

    @Column(name = "origin_lon", nullable = false, updatable = false)
    private double originLon;

    @Column(name = "tile_delta_lat", nullable = false, updatable = false)
    private double tileDeltaLat;

    @Column(name = "tile_delta_lon", nullable = false, updatable = false)
    private double tileDeltaLon;
}
