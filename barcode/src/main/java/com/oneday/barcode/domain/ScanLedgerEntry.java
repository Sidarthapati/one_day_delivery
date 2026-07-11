package com.oneday.barcode.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable row per physical scan of a parcel (M8 §4). Append-only by construction: no setters,
 * every column {@code updatable = false}, and a DB trigger ({@code V8_1}) rejects UPDATE/DELETE.
 *
 * <p>Keyed on {@code shipmentId} (design D-001) — the same UUID routing/hub already pass. The
 * {@code parcelId} barcode string is null until {@code LABEL_GENERATED}. {@code scanType} is a plain
 * string spanning both families (ScanEventType ∪ VanScanType), so no single Java enum fits it.</p>
 */
@Entity
@Table(name = "scan_ledger")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanLedgerEntry {

    // id and recordedAt are also defaulted in the DB (gen_random_uuid() / now(), V8_1). Hibernate
    // wins for app inserts; the DB defaults exist so a raw-SQL insert from another service — which the
    // append-only ledger explicitly anticipates — still gets a valid id/timestamp without them.
    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(name = "parcel_id", length = 30, updatable = false)
    private String parcelId;

    @Column(name = "scan_type", nullable = false, length = 24, updatable = false)
    private String scanType;

    @Column(name = "location_type", nullable = false, length = 32, updatable = false)
    private String locationType;

    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "counterparty_id", updatable = false)
    private UUID counterpartyId;

    @Column(name = "scanned_at", nullable = false, updatable = false)
    private Instant scannedAt;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "client_scan_id", updatable = false)
    private UUID clientScanId;
}
