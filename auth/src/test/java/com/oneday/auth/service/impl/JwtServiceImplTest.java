package com.oneday.auth.service.impl;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceImplTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha256";

    private JwtServiceImpl jwtService;

    @BeforeEach
    void setup() {
        jwtService = new JwtServiceImpl(SECRET, 8L);
    }

    // ── CREATE TOKEN ──────────────────────────────────────────────────────────

    @Test
    void createToken_returnsNonBlankToken() {
        User user = fakeUser("ADMIN", null);

        String token = jwtService.createToken(user);

        assertThat(token).isNotBlank();
        // JWT format: header.payload.signature
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void createToken_subjectIsUserId() {
        User user = fakeUser("ADMIN", null);
        UUID userId = user.getId();

        String token = jwtService.createToken(user);
        Claims claims = jwtService.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
    }

    @Test
    void createToken_claimsContainRoleAndCityId() {
        User user = fakeUser("STATION_MANAGER", "MUM");

        String token = jwtService.createToken(user);
        Claims claims = jwtService.parseToken(token);

        assertThat(claims.get("role", String.class)).isEqualTo("STATION_MANAGER");
        assertThat(claims.get("cityId", String.class)).isEqualTo("MUM");
    }

    @Test
    void createToken_adminHasNullCityId() {
        User user = fakeUser("ADMIN", null);

        String token = jwtService.createToken(user);
        Claims claims = jwtService.parseToken(token);

        assertThat(claims.get("cityId")).isNull();
    }

    @Test
    void createToken_claimsContainName() {
        User user = fakeUser("ADMIN", null);

        String token = jwtService.createToken(user);
        Claims claims = jwtService.parseToken(token);

        assertThat(claims.get("name", String.class)).isEqualTo("Riya");
    }

    @Test
    void createToken_mustChangePasswordClaimPresent() {
        User user = fakeUser("ADMIN", null);
        user.setMustChangePassword(true);

        String token = jwtService.createToken(user);
        Claims claims = jwtService.parseToken(token);

        assertThat(claims.get("mustChangePassword", Boolean.class)).isTrue();
    }

    // ── PARSE TOKEN ───────────────────────────────────────────────────────────

    @Test
    void parseToken_validToken_returnsCorrectClaims() {
        User user = fakeUser("HUB_OPERATOR", "DEL");
        String token = jwtService.createToken(user);

        Claims claims = jwtService.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.get("role", String.class)).isEqualTo("HUB_OPERATOR");
        assertThat(claims.get("cityId", String.class)).isEqualTo("DEL");
    }

    @Test
    void parseToken_tamperedToken_throwsJwtException() {
        User user = fakeUser("ADMIN", null);
        String token = jwtService.createToken(user);

        // Tamper with the payload part
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "TAMPERED" + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parseToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parseToken_wrongSecret_throwsJwtException() {
        User user = fakeUser("ADMIN", null);
        // Token created with a different secret
        JwtServiceImpl otherService = new JwtServiceImpl("completely-different-secret-key-value-here", 8L);
        String token = otherService.createToken(user);

        assertThatThrownBy(() -> jwtService.parseToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parseToken_expiredToken_throwsJwtException() {
        // Service with 0-hour expiry → token already expired
        JwtServiceImpl shortLivedService = new JwtServiceImpl(SECRET, 0L);
        User user = fakeUser("ADMIN", null);
        String token = shortLivedService.createToken(user);

        assertThatThrownBy(() -> jwtService.parseToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parseToken_garbage_throwsJwtException() {
        assertThatThrownBy(() -> jwtService.parseToken("not.a.jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parseToken_blankString_throwsJwtException() {
        assertThatThrownBy(() -> jwtService.parseToken(""))
                .isInstanceOf(Exception.class);
    }

    // ── EXPIRY ────────────────────────────────────────────────────────────────

    @Test
    void expiryFor_returns8HoursFromNow() {
        User user = fakeUser("ADMIN", null);
        Instant before = Instant.now();

        Instant expiry = jwtService.expiryFor(user);

        Instant after = Instant.now();
        // Should be ~8 hours from now (with small tolerance)
        assertThat(expiry).isAfter(before.plusSeconds(8 * 3600 - 5));
        assertThat(expiry).isBefore(after.plusSeconds(8 * 3600 + 5));
    }

    @Test
    void expiryFor_customExpiry_returnsCorrectDuration() {
        JwtServiceImpl twoHourService = new JwtServiceImpl(SECRET, 2L);
        User user = fakeUser("ADMIN", null);
        Instant before = Instant.now();

        Instant expiry = twoHourService.expiryFor(user);

        assertThat(expiry).isAfter(before.plusSeconds(2 * 3600 - 5));
        assertThat(expiry).isBefore(before.plusSeconds(2 * 3600 + 5));
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

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
