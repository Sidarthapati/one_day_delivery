package com.oneday.airline.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

/**
 * A recurring flight slot on an origin→dest lane (e.g. "the 06:00 DEL→BOM every day"). {@link
 * FlightInstance} is a specific date's occurrence of one of these slots. Mutable: ops can retire a
 * slot ({@code active = false}) without deleting history already booked against its instances.
 */
@Entity
@Table(name = "flight_schedule")
@Getter
@Setter
@NoArgsConstructor
public class FlightSchedule extends MutableBaseEntity {

    @Column(name = "origin_hub", length = 10, nullable = false)
    private String originHub;

    @Column(name = "dest_hub", length = 10, nullable = false)
    private String destHub;

    @Column(name = "carrier", length = 30, nullable = false)
    private String carrier;

    @Column(name = "flight_no", length = 20, nullable = false)
    private String flightNo;

    @Column(name = "departure_time", nullable = false)
    private LocalTime departureTime;

    @Column(name = "arrival_time", nullable = false)
    private LocalTime arrivalTime;

    /** Bitmask, bit0=Monday..bit6=Sunday; 127 = every day. */
    @Column(name = "days_of_week", nullable = false)
    private short daysOfWeek;

    @Column(name = "capacity_kg", nullable = false)
    private int capacityKg;

    @Column(name = "active", nullable = false)
    private boolean active;
}
