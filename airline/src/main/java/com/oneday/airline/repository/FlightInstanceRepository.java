package com.oneday.airline.repository;

import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.domain.FlightInstanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlightInstanceRepository extends JpaRepository<FlightInstance, UUID> {

    Optional<FlightInstance> findByFlightNoAndFlightDate(String flightNo, LocalDate flightDate);

    /** Locked read for the booked_weight_grams increment on booking (§3's running weight commitment). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FlightInstance f WHERE f.flightNo = :flightNo AND f.flightDate = :flightDate")
    Optional<FlightInstance> findByFlightNoAndFlightDateForUpdate(
            @Param("flightNo") String flightNo, @Param("flightDate") LocalDate flightDate);

    /** Scope for the status poll job (§13): flights not yet landed/cancelled. */
    List<FlightInstance> findByStatusIn(List<FlightInstanceStatus> statuses);
}
