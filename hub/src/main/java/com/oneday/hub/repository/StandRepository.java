package com.oneday.hub.repository;

import com.oneday.hub.domain.Stand;
import com.oneday.hub.domain.StandStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StandRepository extends JpaRepository<Stand, UUID> {

    /**
     * In-service stands at a hub that are NOT currently holding an open bag of <i>either</i> kind —
     * the free pool a new bag (flight or delivery) can be allocated onto (dynamic stand assignment,
     * M7-D-001). One shared pool, no kind: a free stand is one with no open {@code flight_bag} and no
     * open {@code delivery_bag} on it. Ordered so the caller's {@code preferredZone} comes first
     * (soft floor-area hint — flight bags prefer the airport dock, delivery bags the delivery dock),
     * then by zone, stand_no for determinism.
     */
    @Query("""
            SELECT s FROM Stand s
            WHERE s.hubId = :hubId AND s.status = :standStatus
              AND s.id NOT IN (SELECT b.currentStandId FROM FlightBag b WHERE b.status = com.oneday.hub.domain.FlightBagStatus.OPEN)
              AND s.id NOT IN (SELECT d.currentStandId FROM DeliveryBag d WHERE d.status = com.oneday.hub.domain.DeliveryBagStatus.OPEN)
            ORDER BY CASE WHEN s.zone = :preferredZone THEN 0 ELSE 1 END, s.zone, s.standNo
            """)
    List<Stand> findFreeStands(@Param("hubId") UUID hubId,
                               @Param("standStatus") StandStatus standStatus,
                               @Param("preferredZone") String preferredZone);
}
