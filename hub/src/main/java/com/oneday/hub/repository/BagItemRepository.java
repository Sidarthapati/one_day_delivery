package com.oneday.hub.repository;

import com.oneday.hub.domain.BagItem;
import com.oneday.hub.domain.BagItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BagItemRepository extends JpaRepository<BagItem, UUID> {

    List<BagItem> findByBagId(UUID bagId);

    List<BagItem> findByBagIdAndStatus(UUID bagId, BagItemStatus status);

    boolean existsByParcelIdAndStatus(UUID parcelId, BagItemStatus status);
}
