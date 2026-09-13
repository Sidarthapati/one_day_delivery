package com.oneday.auth.domain;

import com.oneday.common.domain.BaseEntity;
import com.oneday.common.domain.Shift;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A self-service DA onboarding candidate — filled in by the applicant through an invite link, then
 * reviewed in the pre-approval queue. On approval it is converted into a real {@code users} +
 * {@link DaProfile} record; this row is the pre-hire funnel, not the HR profile.
 */
@Getter
@Setter
@Entity
@Table(name = "da_onboarding_candidate")
public class DaOnboardingCandidate extends BaseEntity {

    @Column(name = "invite_token", nullable = false, unique = true, length = 64)
    private String inviteToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OnboardingStatus status = OnboardingStatus.DRAFT;

    // ── Personal ────────────────────────────────────────────────────────────────
    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column
    private LocalDate dob;

    @Column(length = 20)
    private String aadhaar;

    @Column(length = 15)
    private String pan;

    @Column(name = "driving_license", length = 30)
    private String drivingLicense;

    // ── Location / assignment (city scope + free-text branch label; no station entity yet) ──
    @Column(name = "city_id", length = 10)
    private String cityId;

    @Column(name = "station_label", length = 120)
    private String stationLabel;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Shift shift;

    // ── Bank ────────────────────────────────────────────────────────────────────
    @Column(name = "bank_account_number", length = 30)
    private String bankAccountNumber;

    @Column(length = 15)
    private String ifsc;

    @Column(name = "account_holder_name", length = 120)
    private String accountHolderName;

    // ── Agreement + training (click-to-accept; populated in S4) ──────────────────
    @Column(name = "agreement_accepted_at")
    private Instant agreementAcceptedAt;

    @Column(name = "agreement_doc_key")
    private String agreementDocKey;

    @Column(name = "training_ack_at")
    private Instant trainingAckAt;

    // ── Funnel bookkeeping ───────────────────────────────────────────────────────
    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "provisioned_user_id")
    private UUID provisionedUserId;

    @Column(name = "employee_id", length = 40)
    private String employeeId;
}
