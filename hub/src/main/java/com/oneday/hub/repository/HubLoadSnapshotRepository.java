package com.oneday.hub.repository;

import com.oneday.hub.domain.HubLoadSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface HubLoadSnapshotRepository extends JpaRepository<HubLoadSnapshot, UUID> {

    Optional<HubLoadSnapshot> findFirstByHubIdAndWaveKeyOrderBySnapshotAtDesc(UUID hubId, String waveKey);
}
