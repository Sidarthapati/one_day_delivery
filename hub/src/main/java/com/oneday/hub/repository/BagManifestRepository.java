package com.oneday.hub.repository;

import com.oneday.hub.domain.BagManifest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BagManifestRepository extends JpaRepository<BagManifest, UUID> {

    /** The current (latest-generated) manifest for a bag — supersedes chain head. */
    Optional<BagManifest> findFirstByBagIdOrderByGeneratedAtDesc(UUID bagId);
}
