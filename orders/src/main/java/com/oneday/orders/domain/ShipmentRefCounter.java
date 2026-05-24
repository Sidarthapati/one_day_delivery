package com.oneday.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "shipment_ref_counters")
@Getter
@Setter
@NoArgsConstructor
public class ShipmentRefCounter {

    @EmbeddedId
    private ShipmentRefCounterId id;

    @Column(name = "next_val", nullable = false)
    private Integer nextVal;
}
