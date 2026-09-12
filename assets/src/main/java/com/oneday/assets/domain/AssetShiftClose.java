package com.oneday.assets.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A1 — the persisted asset-registry close for one station shift. When the on-shift station manager
 * closes the shift, the current custody of every asset is snapshotted: how many are back at the station,
 * how many are still out with a DA or in maintenance, and — the one enforced rule — whether the vans are
 * back. The incoming shift's manager reads the previous close as their opening state.
 */
@Entity
@Table(name = "asset_shift_close")
@Getter
@Setter
@NoArgsConstructor
public class AssetShiftClose extends MutableBaseEntity {

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    /** The shift that was closed (e.g. SHIFT_1 / SHIFT_2), as named by the console. */
    @Column(name = "shift", length = 20, nullable = false, updatable = false)
    private String shift;

    @Column(name = "close_date", nullable = false, updatable = false)
    private LocalDate closeDate;

    @Column(name = "closed_by_user_id", updatable = false)
    private UUID closedByUserId;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    @Column(name = "total_assets", nullable = false)
    private int totalAssets;

    @Column(name = "at_station_count", nullable = false)
    private int atStationCount;

    @Column(name = "out_with_da_count", nullable = false)
    private int outWithDaCount;

    @Column(name = "in_maintenance_count", nullable = false)
    private int inMaintenanceCount;

    @Column(name = "vans_total", nullable = false)
    private int vansTotal;

    @Column(name = "vans_outstanding", nullable = false)
    private int vansOutstanding;

    /** Vans not back at the station store at close — the one enforced discrepancy. */
    @Column(name = "discrepancy_count", nullable = false)
    private int discrepancyCount;

    /** The still-out assets at close (who holds what), snapshotted for the record. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outstanding")
    private List<OutstandingItem> outstanding;

    /** One still-out asset in the close snapshot. */
    public record OutstandingItem(UUID assetId, String assetTag, String name, String category,
                                  String status, String holderName, boolean isVan, boolean returnRequested) {
    }
}
