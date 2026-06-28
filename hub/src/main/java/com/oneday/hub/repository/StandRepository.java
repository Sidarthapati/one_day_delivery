package com.oneday.hub.repository;

import com.oneday.hub.domain.BagStatus;
import com.oneday.hub.domain.Stand;
import com.oneday.hub.domain.StandStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StandRepository extends JpaRepository<Stand, UUID> {

    /**
     * In-service stands at a hub that are NOT currently holding an open bag — the free pool a new
     * bag (flight or delivery) can be allocated onto (dynamic stand assignment). One shared pool, no
     * kind. Ordered by zone then stand_no so allocation is deterministic and prefers the matching
     * floor area (e.g. flight bags fill the airport-dock shelves first).
     */
    @Query("""
            SELECT s FROM Stand s
            WHERE s.hubId = :hubId AND s.status = :standStatus
              AND s.id NOT IN (SELECT b.currentStandId FROM FlightBag b WHERE b.status = :bagOpen)
            ORDER BY s.zone, s.standNo
            """)
    List<Stand> findFreeStands(@Param("hubId") UUID hubId,
                               @Param("standStatus") StandStatus standStatus,
                               @Param("bagOpen") BagStatus bagOpen);
}
