package com.oneday.auth.service.impl;

import com.oneday.auth.config.RefreshTokenProperties;
import com.oneday.auth.domain.RefreshToken;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.exception.InvalidRefreshTokenException;
import com.oneday.auth.repository.RefreshTokenRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for refresh-token rotation + revocation. The repository is backed by an in-memory
 * hash→entity map so rotation lineage and reuse detection can be exercised without a DB.
 */
class RefreshTokenServiceImplTest {

    private Map<String, RefreshToken> store;
    private RefreshTokenRepository repository;
    private UserRepository userRepository;
    private RefreshTokenServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        repository = mock(RefreshTokenRepository.class);
        userRepository = mock(UserRepository.class);

        when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken t = inv.getArgument(0);
            if (idOf(t) == null) {
                setId(t, UUID.randomUUID());
            }
            store.put(t.getTokenHash(), t);
            return t;
        });
        when(repository.findByTokenHash(any())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(repository.revokeFamily(any(), any())).thenAnswer(inv -> {
            UUID fam = inv.getArgument(0);
            Instant now = inv.getArgument(1);
            int n = 0;
            for (RefreshToken t : store.values()) {
                if (t.getFamilyId().equals(fam) && t.getRevokedAt() == null) {
                    t.setRevokedAt(now);
                    n++;
                }
            }
            return n;
        });

        Role role = new Role();
        role.setName("C2C_CUSTOMER");
        user = new User();
        setId(user, UUID.randomUUID());
        user.setRole(role);
        user.setActive(true);
        when(userRepository.findActiveByIdWithRole(any())).thenReturn(Optional.of(user));

        RefreshTokenProperties props = new RefreshTokenProperties();
        props.setTtl(Duration.ofDays(14));
        service = new RefreshTokenServiceImpl(repository, userRepository, props);
    }

    @Test
    void issue_returnsRawTokenAndPersistsOnlyHash() {
        RefreshTokenService.Issued issued = service.issue(user);

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.expiresAt()).isAfter(Instant.now());
        // Stored under the SHA-256 hash — never the raw token itself.
        assertThat(store).hasSize(1);
        assertThat(store).doesNotContainKey(issued.rawToken());
    }

    @Test
    void rotate_revokesOldMintsNewInSameFamily() {
        RefreshTokenService.Issued first = service.issue(user);
        RefreshToken firstEntity = store.values().iterator().next();

        RefreshTokenService.Rotation rot = service.rotate(first.rawToken());

        assertThat(rot.rawToken()).isNotEqualTo(first.rawToken());
        assertThat(firstEntity.getRevokedAt()).isNotNull();
        assertThat(firstEntity.getReplacedById()).isNotNull();
        // New token shares the family (rotation lineage).
        RefreshToken newEntity = store.get(sha256Hex(rot.rawToken()));
        assertThat(newEntity.getFamilyId()).isEqualTo(firstEntity.getFamilyId());
        assertThat(newEntity.isActive()).isTrue();
    }

    @Test
    void rotate_reuseOfRevokedToken_revokesWholeFamilyAndThrows() {
        RefreshTokenService.Issued first = service.issue(user);
        RefreshTokenService.Rotation rot = service.rotate(first.rawToken()); // first now revoked

        // Replaying the already-rotated first token = theft signal.
        assertThatThrownBy(() -> service.rotate(first.rawToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(repository, atLeastOnce()).revokeFamily(any(), any());
        // The successor is now revoked too — the family is dead.
        RefreshToken successor = store.get(sha256Hex(rot.rawToken()));
        assertThat(successor.getRevokedAt()).isNotNull();
    }

    @Test
    void rotate_expiredToken_throws() {
        RefreshTokenService.Issued first = service.issue(user);
        RefreshToken entity = store.values().iterator().next();
        entity.setExpiresAt(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.rotate(first.rawToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void rotate_unknownToken_throws() {
        assertThatThrownBy(() -> service.rotate("never-issued"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revoke_marksTokenRevoked_andIsIdempotent() {
        RefreshTokenService.Issued first = service.issue(user);
        RefreshToken entity = store.values().iterator().next();

        service.revoke(first.rawToken());
        assertThat(entity.getRevokedAt()).isNotNull();

        // Idempotent — revoking again doesn't blow up or change anything.
        Instant firstRevokedAt = entity.getRevokedAt();
        service.revoke(first.rawToken());
        assertThat(entity.getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    // --- reflection helpers for BaseEntity#id (package-private, generated by JPA in prod) ---

    private static String sha256Hex(String input) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static UUID idOf(Object entity) {
        try {
            Field f = com.oneday.common.domain.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            return (UUID) f.get(entity);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = com.oneday.common.domain.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
