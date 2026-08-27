package com.oneday.assets.repository;

import com.oneday.assets.domain.AssetCustodyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssetCustodyEventRepository extends JpaRepository<AssetCustodyEvent, UUID> {

    /** The full chain of custody for one asset, oldest first. */
    List<AssetCustodyEvent> findByAssetIdOrderByRecordedAtAsc(UUID assetId);

    /** Idempotency: has this client-supplied exchange id already been recorded? */
    boolean existsByClientEventId(String clientEventId);
}
