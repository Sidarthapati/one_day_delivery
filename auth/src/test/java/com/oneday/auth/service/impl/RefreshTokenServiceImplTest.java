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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        // All mutating answers synchronize on `store` so the mock models a serialized DB — the
        // concurrency test relies on revokeIfActive being atomic (only the first caller flips the row).
        when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> {
            synchronized (store) {
                RefreshToken t = inv.getArgument(0);
                if (idOf(t) == null) {
                    setId(t, UUID.randomUUID());
                }
                store.put(t.getTokenHash(), t);
                return t;
            }
        });
        when(repository.findByTokenHash(any())).thenAnswer(inv -> {
            synchronized (store) {
                return Optional.ofNullable(store.get(inv.getArgument(0, String.class)));
            }
        });
        // Atomic claim: revoke only if currently active; return 1 to exactly one concurrent caller.
        when(repository.revokeIfActive(any(), any())).thenAnswer(inv -> {
            synchronized (store) {
                RefreshToken t = store.get(inv.getArgument(0, String.class));
                if (t != null && t.getRevokedAt() == null) {
                    t.setRevokedAt(inv.getArgument(1));
                    return 1;
                }
                return 0;
            }
        });
        when(repository.revokeFamily(any(), any())).thenAnswer(inv -> {
            synchronized (store) {
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
            }
        });
        // Single-active-device sweep: revoke this user's active tokens bound to any other device.
        when(repository.revokeOtherDevices(any(), any(), any())).thenAnswer(inv -> {
            synchronized (store) {
                UUID uid = inv.getArgument(0);
                String dev = inv.getArgument(1);
                Instant now = inv.getArgument(2);
                int n = 0;
                for (RefreshToken t : store.values()) {
                    boolean sameUser = t.getUser() != null && uid.equals(idOf(t.getUser()));
                    boolean otherDevice = t.getDeviceId() == null || !t.getDeviceId().equals(dev);
                    if (sameUser && t.getRevokedAt() == null && otherDevice) {
                        t.setRevokedAt(now);
                        n++;
                    }
                }
                return n;
            }
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
    void issue_bindsDevice_andRotationCarriesItForward() {
        RefreshTokenService.Issued issued = service.issue(user, "device-1");
        RefreshToken entity = store.values().iterator().next();
        assertThat(entity.getDeviceId()).isEqualTo("device-1");

        // A rotation stays tied to the same physical device (the session, not the token, is bound).
        RefreshTokenService.Rotation rot = service.rotate(issued.rawToken());
        RefreshToken next = store.get(sha256Hex(rot.rawToken()));
        assertThat(next.getDeviceId()).isEqualTo("device-1");
    }

    @Test
    void revokeOtherDevices_kicksSessionsOnOtherDevices() {
        service.issue(user, "old-device"); // an active session on the DA's previous phone

        int revoked = service.revokeOtherDevices(user, "new-device");

        assertThat(revoked).isEqualTo(1);
        assertThat(store.values().stream().filter(RefreshToken::isActive).count()).isZero();
    }

    @Test
    void revokeOtherDevices_noopWhenDeviceIdBlank() {
        service.issue(user, "old-device");
        assertThat(service.revokeOtherDevices(user, null)).isZero();
        assertThat(store.values().stream().filter(RefreshToken::isActive).count()).isEqualTo(1);
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
    void rotate_laterReuseOfRotatedToken_revokesWholeFamilyAndThrows() {
        RefreshTokenService.Issued first = service.issue(user);
        RefreshToken firstEntity = store.values().iterator().next();
        RefreshTokenService.Rotation rot = service.rotate(first.rawToken()); // first now revoked

        // Simulate a genuine *later* replay of the long-rotated token (well beyond the grace window) —
        // a stolen-token replay, not a near-simultaneous double-submit.
        firstEntity.setRevokedAt(Instant.now().minusSeconds(120));

        assertThatThrownBy(() -> service.rotate(first.rawToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(repository, atLeastOnce()).revokeFamily(any(), any());
        // The successor is now revoked too — the family is dead.
        RefreshToken successor = store.get(sha256Hex(rot.rawToken()));
        assertThat(successor.getRevokedAt()).isNotNull();
    }

    @Test
    void rotate_concurrentRequests_exactlyOneSucceeds_familyNotNuked() throws Exception {
        RefreshTokenService.Issued issued = service.issue(user);

        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(); // all threads pile onto the same token at once
                try {
                    service.rotate(issued.rawToken());
                    ok.incrementAndGet();
                } catch (InvalidRefreshTokenException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // The invariant: a refresh token is used at most once — exactly one rotation succeeds.
        assertThat(ok.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(n - 1);
        // A benign concurrent double-submit must not nuke the family — the winner's successor is the
        // single surviving active token.
        assertThat(store.values().stream().filter(RefreshToken::isActive).count()).isEqualTo(1);
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
