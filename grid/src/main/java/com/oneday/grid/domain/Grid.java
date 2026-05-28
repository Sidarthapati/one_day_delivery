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
@Table(name = "h3_grid")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Grid extends BaseEntity {

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "h3_resolution", nullable = false, updatable = false)
    private int h3Resolution;
}
