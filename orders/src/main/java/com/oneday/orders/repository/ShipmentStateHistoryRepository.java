package com.oneday.orders.repository;

import com.oneday.orders.domain.ShipmentStateHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShipmentStateHistoryRepository extends JpaRepository<ShipmentStateHistory, UUID> {

    List<ShipmentStateHistory> findByShipmentIdOrderByOccurredAtAsc(UUID shipmentId);
}
