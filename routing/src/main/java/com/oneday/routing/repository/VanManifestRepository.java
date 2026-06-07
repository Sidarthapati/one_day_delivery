package com.oneday.routing.repository;

import com.oneday.routing.domain.VanManifest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VanManifestRepository extends JpaRepository<VanManifest, UUID> {

    Optional<VanManifest> findByVanIdAndLoopIndexAndValidDate(UUID vanId, int loopIndex, LocalDate validDate);

    List<VanManifest> findByRoutePlanId(UUID routePlanId);
}
