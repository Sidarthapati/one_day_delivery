package com.oneday.airline.repository;

import com.oneday.airline.domain.FlightSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlightScheduleRepository extends JpaRepository<FlightSchedule, UUID> {

    List<FlightSchedule> findByOriginHubAndDestHubAndActiveTrue(String originHub, String destHub);

    /** flight_no is globally unique (V9_1) — used to resolve a bag's lane from its flight number alone. */
    Optional<FlightSchedule> findByFlightNo(String flightNo);
}
