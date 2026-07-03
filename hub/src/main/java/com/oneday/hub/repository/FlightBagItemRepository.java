package com.oneday.hub.repository;

import com.oneday.hub.domain.FlightBagItem;
import com.oneday.hub.domain.FlightBagItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FlightBagItemRepository extends JpaRepository<FlightBagItem, UUID> {

    List<FlightBagItem> findByBagId(UUID bagId);

    List<FlightBagItem> findByBagIdAndStatus(UUID bagId, FlightBagItemStatus status);

    boolean existsByParcelIdAndStatus(UUID parcelId, FlightBagItemStatus status);
}
