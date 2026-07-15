package com.oneday.airline.repository;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AwbRepository extends JpaRepository<Awb, UUID> {

    /**
     * Idempotency check: at most one <b>live</b> booking per sealed bag (§6, §11). A reassignment
     * (§7) can leave more than one row for the same bag over time — the old one SUPERSEDED, a new one
     * BOOKED — so this is scoped to the current BOOKED row, not "the" row for the bag.
     */
    Optional<Awb> findByBagIdAndStatus(UUID bagId, AwbStatus status);

    List<Awb> findByFlightNoAndFlightDate(String flightNo, LocalDate flightDate);

    List<Awb> findByOriginHubAndFlightDate(String originHub, LocalDate flightDate);
}
