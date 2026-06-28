package com.oneday.hub.repository;

import com.oneday.hub.domain.DeliveryBagItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryBagItemRepository extends JpaRepository<DeliveryBagItem, UUID> {

    List<DeliveryBagItem> findByCityIdAndLoopHint(UUID cityId, Integer loopHint);
}
