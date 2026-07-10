package com.oneday.hub.repository;

import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.domain.FlightBag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlightBagRepository extends JpaRepository<FlightBag, UUID> {

    /** The lazy-create lookup: the open bag for a (flight, date, dest_hub), if one exists. */
    Optional<FlightBag> findByFlightNoAndFlightDateAndDestHubAndStatus(
            String flightNo, LocalDate flightDate, String destHub, FlightBagStatus status);

    /**
     * Reassignment source resolution: the still-at-hub bag for a (flight, dest_hub) keyed on flight
     * number. Matches OPEN <i>or</i> SEALED — a cancelled flight's bag may already be sealed.
     */
    Optional<FlightBag> findFirstByFlightNoAndDestHubAndStatusInOrderByCreatedAtDesc(
            String flightNo, String destHub, java.util.Collection<FlightBagStatus> statuses);

    /** Operator console: the day's flight bags at a hub (open = the live origin directory). */
    List<FlightBag> findByHubIdAndFlightDate(UUID hubId, LocalDate flightDate);

    long countByHubIdAndStatus(UUID hubId, FlightBagStatus status);

    /** Parcels sitting in still-open flight bags (in-progress sort backlog proxy, §11). */
    @Query("SELECT COALESCE(SUM(b.parcelCount), 0) FROM FlightBag b "
            + "WHERE b.hubId = :hubId AND b.status = com.oneday.hub.domain.FlightBagStatus.OPEN")
    long sumOpenParcelCount(UUID hubId);
}
