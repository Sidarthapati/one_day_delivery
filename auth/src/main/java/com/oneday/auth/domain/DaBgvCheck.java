package com.oneday.auth.domain;

import com.oneday.common.domain.BaseEntity;
import com.oneday.common.port.dto.bgv.BgvCheckStatus;
import com.oneday.common.port.dto.bgv.BgvCheckType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One background-verification check for a candidate — its type, current status/verdict, and vendor ref. */
@Getter
@Setter
@Entity
@Table(name = "da_bgv_check")
public class DaBgvCheck extends BaseEntity {

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 20)
    private BgvCheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private BgvCheckStatus status = BgvCheckStatus.NOT_INITIATED;

    @Column(name = "vendor_ref", length = 80)
    private String vendorRef;

    @Column(length = 300)
    private String message;

    @Column(name = "initiated_at")
    private Instant initiatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
