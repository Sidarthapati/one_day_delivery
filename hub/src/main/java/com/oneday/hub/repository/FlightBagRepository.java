package com.oneday.hub.repository;

import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.domain.FlightBag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface FlightBagRepository extends JpaRepository<FlightBag, UUID> {

    /** The lazy-create lookup: the open bag for a (flight, date, dest_hub), if one exists. */
    Optional<FlightBag> findByFlightNoAndFlightDateAndDestHubAndStatus(
            String flightNo, LocalDate flightDate, String destHub, FlightBagStatus status);
}
