package com.oneday.auth.service.impl;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused unit coverage of JWT minting/parsing. The login → token → protected-call path is covered
 * end-to-end by the e2e suite; this class pins the crypto-level invariants that the HTTP layer can't
 * easily assert: claim contents, signature integrity, and expiry.
 */
@DisplayName("JwtService")
class JwtServiceImplTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha256";

    private JwtServiceImpl jwtService;

    @BeforeEach
    void setup() {
        jwtService = new JwtServiceImpl(SECRET, 8L);
    }

    // A minted token round-trips: parsing it back yields exactly the identity claims that were put in
    // (subject=userId, role, cityId, name, mustChangePassword).
    @Test
    void createToken_roundTripsAllClaims() {
        User user = fakeUser("STATION_MANAGER", "MUM");
        user.setMustChangePassword(true);

        Claims claims = jwtService.parseToken(jwtService.createToken(user));

        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.get("role", String.class)).isEqualTo("STATION_MANAGER");
        assertThat(claims.get("cityId", String.class)).isEqualTo("MUM");
        assertThat(claims.get("name", String.class)).isEqualTo("Riya");
        assertThat(claims.get("mustChangePassword", Boolean.class)).isTrue();
    }

    // A token whose payload was altered fails signature verification.
    @Test
    void parseToken_tampered_throws() {
        String[] parts = jwtService.createToken(fakeUser("ADMIN", null)).split("\\.");
        String tampered = parts[0] + "." + parts[1] + "TAMPERED." + parts[2];

        assertThatThrownBy(() -> jwtService.parseToken(tampered)).isInstanceOf(JwtException.class);
    }

    // A token signed with a different secret is rejected (forged-token defence).
    @Test
    void parseToken_wrongSecret_throws() {
        String foreign = new JwtServiceImpl("completely-different-secret-key-value-here", 8L)
                .createToken(fakeUser("ADMIN", null));

        assertThatThrownBy(() -> jwtService.parseToken(foreign)).isInstanceOf(JwtException.class);
    }

    // An expired token is rejected.
    @Test
    void parseToken_expired_throws() {
        String expired = new JwtServiceImpl(SECRET, 0L).createToken(fakeUser("ADMIN", null));

        assertThatThrownBy(() -> jwtService.parseToken(expired)).isInstanceOf(JwtException.class);
    }

    // Expiry is computed from the configured token lifetime.
    @Test
    void expiryFor_matchesConfiguredHours() {
        Instant before = Instant.now();
        Instant expiry = jwtService.expiryFor(fakeUser("ADMIN", null));

        assertThat(expiry).isAfter(before.plusSeconds(8 * 3600 - 5));
        assertThat(expiry).isBefore(before.plusSeconds(8 * 3600 + 5));
    }

    private static User fakeUser(String roleName, String cityId) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        role.setName(roleName);
        role.setDisplayName(roleName);
        role.setCityScoped(cityId != null);
        role.setBuiltin(true);
        role.setActive(true);
        role.setPermissions(new HashSet<>());

        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail("test@oneday.in");
        user.setPasswordHash("$2a$hash");
        user.setName("Riya");
        user.setRole(role);
        user.setCityId(cityId);
        user.setActive(true);
        user.setMustChangePassword(false);
        return user;
    }
}
