package com.oneday.routing.repository;

import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.VanManifestItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VanManifestItemRepository extends JpaRepository<VanManifestItem, UUID> {

    List<VanManifestItem> findByManifestIdAndStopSeq(UUID manifestId, int stopSeq);

    List<VanManifestItem> findByManifestId(UUID manifestId);

    List<VanManifestItem> findByParcelId(UUID parcelId);

    int countByManifestIdAndDirection(UUID manifestId, HandoffDirection direction);

    List<VanManifestItem> findByManifestIdAndDirection(UUID manifestId, HandoffDirection direction);
}
