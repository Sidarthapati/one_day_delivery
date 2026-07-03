package com.oneday.hub.repository;

import com.oneday.hub.domain.DeliveryBagItem;
import com.oneday.hub.domain.DeliveryBagItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryBagItemRepository extends JpaRepository<DeliveryBagItem, UUID> {

    List<DeliveryBagItem> findByCityIdAndLoopHint(UUID cityId, Integer loopHint);

    /** Contents of a delivery bag (for the seal load-list). */
    List<DeliveryBagItem> findByDeliveryBagIdAndStatus(UUID deliveryBagId, DeliveryBagItemStatus status);

    /** Idempotency guard: is this parcel already staged in some delivery bag / on a shelf? */
    boolean existsByParcelIdAndStatusIn(UUID parcelId, List<DeliveryBagItemStatus> statuses);

    /** Operator console: per-parcel staging view for a city. */
    List<DeliveryBagItem> findByCityIdAndStatus(UUID cityId, DeliveryBagItemStatus status);
}
