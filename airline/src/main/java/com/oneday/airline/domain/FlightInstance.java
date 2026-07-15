package com.oneday.airline.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A specific date's occurrence of a {@link FlightSchedule} slot, created the first time it's needed
 * to carry a bag. {@code bookedKg} is the running per-flight weight commitment (§3) — a live estimate
 * while a bag is still filling, incremented for real once a bag books (§5's "honest limitation").
 */
@Entity
@Table(name = "flight_instance")
@Getter
@Setter
@NoArgsConstructor
public class FlightInstance extends MutableBaseEntity {

    @Column(name = "flight_no", length = 20, nullable = false)
    private String flightNo;

    @Column(name = "flight_date", nullable = false)
    private LocalDate flightDate;

    @Column(name = "origin_hub", length = 10, nullable = false)
    private String originHub;

    @Column(name = "dest_hub", length = 10, nullable = false)
    private String destHub;

    @Column(name = "departure", nullable = false)
    private Instant departure;

    @Column(name = "arrival", nullable = false)
    private Instant arrival;

    @Column(name = "cutoff", nullable = false)
    private Instant cutoff;

    @Column(name = "capacity_kg", nullable = false)
    private int capacityKg;

    @Column(name = "booked_weight_grams", nullable = false)
    private int bookedWeightGrams;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private FlightInstanceStatus status;
}
