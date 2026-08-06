package com.oneday.airline.repository;

import com.oneday.airline.domain.AwbParcel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AwbParcelRepository extends JpaRepository<AwbParcel, UUID> {

    List<AwbParcel> findByAwbId(UUID awbId);

    /**
     * A reassignment creates a fresh row per parcel on the replacement AWB rather than mutating the
     * old one (§6/§7 — history preserved), so a parcel can have more than one row over time; the most
     * recently created is always the current (live) booking.
     */
    Optional<AwbParcel> findFirstByParcelIdOrderByCreatedAtDesc(UUID parcelId);
}
