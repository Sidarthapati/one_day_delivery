package com.oneday.orders.repository;

import com.oneday.orders.domain.DeliveryConfirmation;
import com.oneday.orders.domain.DeliveryConfirmationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryConfirmationRepository extends JpaRepository<DeliveryConfirmation, UUID> {

    /** Public-link lookup — resolves the opaque token by its stored SHA-256 hash. */
    Optional<DeliveryConfirmation> findByTokenHash(String tokenHash);

    /** The live prompt for a shipment — the prompt idempotency guard (one PENDING per shipment). */
    Optional<DeliveryConfirmation> findFirstByShipmentIdAndStatus(UUID shipmentId, DeliveryConfirmationStatus status);

    /** Stale PENDING prompts past their expiry — flipped to EXPIRED by the sweep (cosmetic; silence = accept). */
    List<DeliveryConfirmation> findByStatusAndExpiresAtBefore(DeliveryConfirmationStatus status, Instant cutoff);
}
