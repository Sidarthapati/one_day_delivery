package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.ScheduledPickup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledPickupRepository extends JpaRepository<ScheduledPickup, UUID> {

    /** HELD holds now due for release (hits idx_scheduled_pickup_release). */
    List<ScheduledPickup> findByStatusAndReleaseAtLessThanEqual(String status, Instant now);

    /** The live hold for a shipment, if any (for cancellation). */
    Optional<ScheduledPickup> findByShipmentIdAndStatus(UUID shipmentId, String status);
}
