package com.oneday.assets.repository;

import com.oneday.assets.domain.Asset;
import com.oneday.assets.domain.AssetCategory;
import com.oneday.assets.domain.AssetStatus;
import com.oneday.assets.domain.HolderType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    /** Pessimistic lock for a custody write — serializes concurrent issue/return on the same asset. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Asset a WHERE a.id = :id")
    Optional<Asset> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByAssetTag(String assetTag);

    List<Asset> findByCityIdOrderByCreatedAtDesc(UUID cityId);

    List<Asset> findByCityIdAndStatusOrderByCreatedAtDesc(UUID cityId, AssetStatus status);

    List<Asset> findByCityIdAndCategoryOrderByCreatedAtDesc(UUID cityId, AssetCategory category);

    List<Asset> findByCityIdAndCategoryAndStatusOrderByCreatedAtDesc(
            UUID cityId, AssetCategory category, AssetStatus status);

    /** What a holder (a DA) currently has. */
    List<Asset> findByCurrentHolderTypeAndCurrentHolderIdAndStatusOrderByHeldSinceDesc(
            HolderType holderType, UUID holderId, AssetStatus status);

    /** Still-out reconciliation: everything ASSIGNED in a city. */
    List<Asset> findByCityIdAndStatusInOrderByHeldSinceAsc(UUID cityId, List<AssetStatus> statuses);

    Optional<Asset> findByCityIdAndRegistrationNumberIgnoreCase(UUID cityId, String registrationNumber);
}
