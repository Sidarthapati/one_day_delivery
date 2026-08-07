package com.oneday.auth.domain;

import com.oneday.common.domain.Shift;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * HR / contract profile for a DA. Shares its primary key with the {@code users} row (one profile per
 * DELIVERY_ASSOCIATE). The roster query ({@code DaDirectoryPortAdapter}) joins this to {@code users}
 * on {@code user_id} and filters by shift + contract window.
 */
@Entity
@Table(name = "da_profile")
@Getter
@Setter
@NoArgsConstructor
public class DaProfile {

    @Id
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @Column(length = 20)
    private String aadhaar;

    @Column(length = 15)
    private String pan;

    // Text pointer only for now — binary/object upload deferred.
    @Column(name = "pan_doc_url")
    private String panDocUrl;

    @Column(name = "contract_start_date", nullable = false)
    private LocalDate contractStartDate;

    // NULL = active / open-ended contract.
    @Column(name = "contract_end_date")
    private LocalDate contractEndDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Shift shift;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
