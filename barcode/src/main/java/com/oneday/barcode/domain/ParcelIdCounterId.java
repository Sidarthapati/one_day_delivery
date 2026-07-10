package com.oneday.barcode.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/** Composite key for {@link ParcelIdCounter}: one sequence per hub, per day (design §3.2). */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ParcelIdCounterId implements Serializable {

    @Column(name = "hub_iata", length = 3, nullable = false)
    private String hubIata;

    @Column(name = "day", nullable = false)
    private LocalDate day;
}
