package com.oneday.assets.service;

import com.oneday.assets.domain.AssetCategory;
import com.oneday.assets.domain.AssetCondition;
import com.oneday.assets.domain.AssetStatus;
import com.oneday.assets.dto.AssetCustodyEventView;
import com.oneday.assets.dto.AssetView;
import com.oneday.assets.dto.EvidenceUpload;
import com.oneday.assets.dto.RegisterAssetRequest;
import com.oneday.assets.dto.SelectVanRequest;

import java.util.List;
import java.util.UUID;

/**
 * The asset registry + custody API (M13). Station-manager actions carry a {@code scopeCityId} (null for
 * ADMIN) so a manager can only touch their own city's assets; DA-self actions are gated by the caller
 * being that DA. Every custody move is a single transaction: pessimistic-locked pointer update + one
 * append-only ledger row + an AFTER_COMMIT event.
 */
public interface AssetService {

    // ── Station manager ──────────────────────────────────────────────
    List<EvidenceUpload> presignPhotoUploads(int count, UUID cityId);

    AssetView register(RegisterAssetRequest req, UUID cityId, UUID actor);

    List<AssetView> listByCity(UUID cityId, AssetStatus status, AssetCategory category);

    AssetView get(UUID assetId, UUID scopeCityId);

    List<AssetCustodyEventView> history(UUID assetId, UUID scopeCityId);

    AssetView issue(UUID assetId, UUID toDaId, String reason, UUID scopeCityId, UUID actor);

    AssetView returnToStation(UUID assetId, AssetCondition condition, String reason, UUID scopeCityId, UUID actor);

    AssetView transfer(UUID assetId, UUID toDaId, String reason, UUID scopeCityId, UUID actor);

    AssetView sendToMaintenance(UUID assetId, String reason, UUID scopeCityId, UUID actor);

    AssetView returnFromMaintenance(UUID assetId, AssetCondition condition, String reason, UUID scopeCityId, UUID actor);

    AssetView reportLost(UUID assetId, String reason, UUID scopeCityId, UUID actor);

    AssetView reportDamaged(UUID assetId, String reason, UUID scopeCityId, UUID actor);

    AssetView recover(UUID assetId, String reason, UUID scopeCityId, UUID actor);

    AssetView decommission(UUID assetId, String reason, UUID scopeCityId, UUID actor);

    /** Still-out report: assets a city has ASSIGNED (or IN_MAINTENANCE) right now. */
    List<AssetView> reconciliation(UUID cityId);

    // ── DA-self ──────────────────────────────────────────────────────
    List<AssetView> heldBy(UUID daId);

    List<AssetView> availableVans(UUID cityId);

    AssetView selectVan(UUID daId, UUID daCityId, SelectVanRequest req);

    AssetView returnVan(UUID daId);

    AssetView acknowledge(UUID assetId, UUID byDaId);
}
