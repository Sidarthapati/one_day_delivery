package com.oneday.hub.repository;

import com.oneday.hub.domain.DeliveryStaging;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryStagingRepository extends JpaRepository<DeliveryStaging, UUID> {

    List<DeliveryStaging> findByCityIdAndLoopHint(UUID cityId, Integer loopHint);
}
