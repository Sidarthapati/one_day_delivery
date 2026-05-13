package com.oneday.grid.repository;

import com.oneday.grid.domain.TileTravelTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface TileTravelTimeRepository extends JpaRepository<TileTravelTime, UUID> {

    // Bulk delete — used by OsrmMatrixRefreshJob to replace the full matrix for a grid.
    // JPQL bulk delete avoids the N+1 find-then-delete that a derived deleteBy... would do.
    @Modifying
    @Transactional
    @Query("DELETE FROM TileTravelTime t WHERE t.gridId = :gridId")
    void deleteByGridId(@Param("gridId") UUID gridId);

    // Returns only pairs within the adjacency threshold — used to build the road-adjacency graph.
    List<TileTravelTime> findByGridIdAndTravelTimeSecondsLessThanEqual(UUID gridId, int thresholdSeconds);
}
