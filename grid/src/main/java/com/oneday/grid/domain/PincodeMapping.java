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
@Table(name = "h3_pincode_mapping")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PincodeMapping {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "pincode", nullable = false, length = 10, updatable = false)
    private String pincode;

    // Null if the pincode centroid falls outside all active hexes (edge of grid).
    @Column(name = "hex_id")
    private UUID hexId;

    @Column(name = "is_serviceable", nullable = false)
    private boolean serviceable;
}
