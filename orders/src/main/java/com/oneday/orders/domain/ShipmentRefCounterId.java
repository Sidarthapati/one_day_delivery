package com.oneday.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ShipmentRefCounterId implements Serializable {

    @Column(name = "city_code", length = 10, nullable = false)
    private String cityCode;

    @Column(name = "date_key", nullable = false)
    private LocalDate dateKey;
}
