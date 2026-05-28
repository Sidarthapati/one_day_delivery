package com.oneday.grid.repository;

import com.oneday.grid.domain.HexTravelTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface HexTravelTimeRepository extends JpaRepository<HexTravelTime, UUID> {

    @Modifying
    @Transactional
    @Query("DELETE FROM HexTravelTime t WHERE t.h3GridId = :h3GridId")
    void deleteByH3GridId(@Param("h3GridId") UUID h3GridId);

    List<HexTravelTime> findByH3GridIdAndTravelTimeSecondsLessThanEqual(UUID h3GridId, int thresholdSeconds);
}
