package com.oneday.orders.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A GST tax invoice for Godspeed's logistics service — one per shipment, all customer types.
 * Append-only in spirit (issued once); the IRN/QR fields are filled only if/when e-invoiced.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
public class Invoice extends BaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false, unique = true)
    private UUID shipmentId;

    @Column(name = "shipment_ref", length = 30, nullable = false, updatable = false)
    private String shipmentRef;

    @Column(name = "invoice_number", length = 40, nullable = false, updatable = false, unique = true)
    private String invoiceNumber;

    @Column(name = "customer_type", length = 10, nullable = false)
    private String customerType;

    @Column(name = "buyer_user_id")
    private UUID buyerUserId;

    @Column(name = "buyer_name", length = 200)
    private String buyerName;

    @Column(name = "buyer_gstin", length = 15)
    private String buyerGstin;

    @Column(name = "sac_code", length = 10, nullable = false)
    private String sacCode = "996812";

    @Column(name = "taxable_value_paise", nullable = false)
    private Long taxableValuePaise;

    @Column(name = "cgst_paise", nullable = false)
    private Long cgstPaise = 0L;

    @Column(name = "sgst_paise", nullable = false)
    private Long sgstPaise = 0L;

    @Column(name = "igst_paise", nullable = false)
    private Long igstPaise = 0L;

    @Column(name = "total_paise", nullable = false)
    private Long totalPaise;

    @Column(name = "irn", length = 80)
    private String irn;

    @Column(name = "irn_qr")
    private String irnQr;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "ISSUED";

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
}
