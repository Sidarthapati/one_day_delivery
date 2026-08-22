package com.oneday.orders.repository;

import com.oneday.orders.domain.ParcelMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ParcelMeasurementRepository extends JpaRepository<ParcelMeasurement, UUID> {

    /** All observations for a shipment, newest first (append-only history). */
    List<ParcelMeasurement> findByShipmentIdOrderByCreatedAtDesc(UUID shipmentId);
}
