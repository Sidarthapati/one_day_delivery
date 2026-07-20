package com.oneday.auth.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    // Nullable: OTP-only users identify by phone and may have no email. Still unique when present.
    @Column(unique = true)
    private String email;

    // Nullable: social (Google) and phone-OTP users have no password.
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column(length = 15)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    private AuthProvider authProvider = AuthProvider.LOCAL;

    // Stable Google account id (OIDC "sub") for GOOGLE users; null otherwise.
    @Column(name = "provider_subject")
    private String providerSubject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "city_id", length = 50)
    private String cityId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;
}
