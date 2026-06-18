package com.oneday.routing.repository;

import com.oneday.routing.domain.VanManifest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VanManifestRepository extends JpaRepository<VanManifest, UUID> {

    Optional<VanManifest> findByVanIdAndLoopIndexAndValidDate(UUID vanId, int loopIndex, LocalDate validDate);

    // SELECT FOR UPDATE: lock the loop's manifest row before counting capacity so binds can't race.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from VanManifest m where m.vanId = :van and m.loopIndex = :loop and m.validDate = :date")
    Optional<VanManifest> lockByVanLoopDate(@Param("van") UUID vanId, @Param("loop") int loopIndex, @Param("date") LocalDate validDate);

    List<VanManifest> findByRoutePlanId(UUID routePlanId);
}
