package com.oneday.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-(city, date) sequence backing the order ref. Mirrors {@link ShipmentRefCounter}. */
@Entity
@Table(name = "order_ref_counters")
@Getter
@Setter
@NoArgsConstructor
public class OrderRefCounter {

    @EmbeddedId
    private OrderRefCounterId id;

    @Column(name = "next_val", nullable = false)
    private Integer nextVal;
}
