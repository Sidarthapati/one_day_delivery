package com.oneday.orders.repository;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    Optional<Shipment> findByShipmentRef(String shipmentRef);

    List<Shipment> findByState(ShipmentState state);

    List<Shipment> findByStateAndCityId(ShipmentState state, String cityId);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
