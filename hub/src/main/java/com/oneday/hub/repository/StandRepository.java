package com.oneday.hub.repository;

import com.oneday.hub.domain.BagStatus;
import com.oneday.hub.domain.Stand;
import com.oneday.hub.domain.StandKind;
import com.oneday.hub.domain.StandStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StandRepository extends JpaRepository<Stand, UUID> {

    List<Stand> findByCityIdAndKind(UUID cityId, StandKind kind);

    List<Stand> findByHubIdAndKindAndStatus(UUID hubId, StandKind kind, StandStatus status);

    /**
     * In-service stands of a kind at a hub that are NOT currently holding an open bag — the free
     * pool a new flight bag can be allocated onto (dynamic stand assignment). Ordered by stand_no so
     * allocation is deterministic (fills A-1, A-2, … in order).
     */
    @Query("""
            SELECT s FROM Stand s
            WHERE s.hubId = :hubId AND s.kind = :kind AND s.status = :standStatus
              AND s.id NOT IN (SELECT b.currentStandId FROM FlightBag b WHERE b.status = :bagOpen)
            ORDER BY s.standNo
            """)
    List<Stand> findFreeStands(@Param("hubId") UUID hubId,
                               @Param("kind") StandKind kind,
                               @Param("standStatus") StandStatus standStatus,
                               @Param("bagOpen") BagStatus bagOpen);
}
