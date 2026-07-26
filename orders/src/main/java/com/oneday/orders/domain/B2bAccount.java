package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "b2b_accounts")
@Getter
@Setter
@NoArgsConstructor
public class B2bAccount extends MutableBaseEntity {

    @Column(name = "account_name", length = 200, nullable = false)
    private String accountName;

    @Column(name = "gstin", length = 15)
    private String gstin;

    @Column(name = "billing_email", length = 254, nullable = false)
    private String billingEmail;

    @Column(name = "credit_limit_paise", nullable = false)
    private Long creditLimitPaise;

    @Column(name = "outstanding_balance_paise", nullable = false)
    private Long outstandingBalancePaise;

    @Column(name = "payment_terms_days", nullable = false)
    private Short paymentTermsDays;

    @Column(name = "rate_card_id")
    private UUID rateCardId;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    @Column(name = "webhook_secret", length = 100)
    private String webhookSecret;

    @Column(name = "city_id", length = 10, nullable = false)
    private String cityId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    // M1 user authorized to book against this account's credit (nullable = unrestricted).
    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    // ── KYC / verification state (V4_23) ───────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 20, nullable = false)
    private B2bVerificationStatus verificationStatus = B2bVerificationStatus.UNVERIFIED;

    @Column(name = "business_type", length = 30)
    private String businessType;

    @Column(name = "pan", length = 10)
    private String pan;

    @Column(name = "gstin_verified")
    private Boolean gstinVerified;

    @Column(name = "pan_verified")
    private Boolean panVerified;

    @Column(name = "bank_account_masked", length = 30)
    private String bankAccountMasked;

    @Column(name = "bank_ifsc", length = 15)
    private String bankIfsc;

    @Column(name = "bank_verified")
    private Boolean bankVerified;

    @Column(name = "kyc_submitted_at")
    private Instant kycSubmittedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
}
