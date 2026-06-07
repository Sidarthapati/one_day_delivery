package com.oneday.routing.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// Hub / airport coordinates per city (M6-D-007). One row per (city_id, kind).
@Entity
@Table(name = "city_logistics_node")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CityLogisticsNode {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10, updatable = false)
    private LogisticsNodeKind kind;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lon", nullable = false)
    private double lon;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
