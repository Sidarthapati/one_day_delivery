package com.oneday.auth.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "onboarding_requests")
public class OnboardingRequest extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    @Column(name = "requested_role", nullable = false)
    private String requestedRole;

    @Column(name = "phone")
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    // ── Business (B2B_USER) onboarding — null for personal onboardings ──────────
    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "business_type", length = 30)
    private String businessType;

    @Column(name = "gstin", length = 15)
    private String gstin;

    @Column(name = "pan", length = 10)
    private String pan;

    @Column(name = "billing_email", length = 254)
    private String billingEmail;

    @Column(name = "city_id", length = 10)
    private String cityId;

    @Column(name = "gstin_verified")
    private Boolean gstinVerified;

    @Column(name = "pan_verified")
    private Boolean panVerified;

    @Column(name = "gstin_legal_name", length = 200)
    private String gstinLegalName;

    @Column(name = "kyc_message", length = 500)
    private String kycMessage;
}
