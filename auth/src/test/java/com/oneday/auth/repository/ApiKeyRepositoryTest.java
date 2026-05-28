package com.oneday.auth.repository;

import com.oneday.auth.domain.ApiKey;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApiKeyRepositoryTest {

    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;

    private User user;

    @BeforeEach
    void setUp() {
        Role role = roleRepository.findByName("ADMIN").orElseThrow();
        User u = new User();
        u.setEmail("apikey-repo-test@test.com");
        u.setPasswordHash("hashed");
        u.setName("ApiKey Test User");
        u.setRole(role);
        u.setActive(true);
        user = userRepository.saveAndFlush(u);
    }

    private ApiKey buildKey(String keyHash, boolean active) {
        ApiKey k = new ApiKey();
        k.setKeyHash(keyHash);
        k.setUser(user);
        k.setLabel("test key");
        k.setActive(active);
        return k;
    }

    // ── findActiveByKeyHashWithUser ───────────────────────────────────────────

    @Test
    void findActiveByKeyHashWithUser_activeKey_returnsKeyWithUserEagerlyLoaded() {
        apiKeyRepository.saveAndFlush(buildKey("hash-active-001", true));

        var result = apiKeyRepository.findActiveByKeyHashWithUser("hash-active-001");

        assertThat(result).isPresent();
        assertThat(result.get().getKeyHash()).isEqualTo("hash-active-001");
        // JOIN FETCH means user and role are loaded — no LazyInitializationException
        assertThat(result.get().getUser().getId()).isEqualTo(user.getId());
        assertThat(result.get().getUser().getRole().getName()).isEqualTo("ADMIN");
    }

    @Test
    void findActiveByKeyHashWithUser_inactiveKey_returnsEmpty() {
        apiKeyRepository.saveAndFlush(buildKey("hash-inactive-001", false));

        assertThat(apiKeyRepository.findActiveByKeyHashWithUser("hash-inactive-001")).isEmpty();
    }

    @Test
    void findActiveByKeyHashWithUser_unknownHash_returnsEmpty() {
        assertThat(apiKeyRepository.findActiveByKeyHashWithUser("no-such-hash")).isEmpty();
    }

    // ── countByUserIdAndActiveTrue ────────────────────────────────────────────

    @Test
    void countByUserIdAndActiveTrue_countsOnlyActiveKeys() {
        apiKeyRepository.saveAndFlush(buildKey("hash-c1", true));
        apiKeyRepository.saveAndFlush(buildKey("hash-c2", true));
        apiKeyRepository.saveAndFlush(buildKey("hash-c3", false));

        assertThat(apiKeyRepository.countByUserIdAndActiveTrue(user.getId())).isEqualTo(2);
    }

    @Test
    void countByUserIdAndActiveTrue_noKeys_returnsZero() {
        assertThat(apiKeyRepository.countByUserIdAndActiveTrue(user.getId())).isZero();
    }

    @Test
    void countByUserIdAndActiveTrue_unknownUser_returnsZero() {
        assertThat(apiKeyRepository.countByUserIdAndActiveTrue(UUID.randomUUID())).isZero();
    }

    // ── findAllByUserId ───────────────────────────────────────────────────────

    @Test
    void findAllByUserId_returnsAllKeysRegardlessOfActiveFlag() {
        apiKeyRepository.saveAndFlush(buildKey("hash-all-active", true));
        apiKeyRepository.saveAndFlush(buildKey("hash-all-inactive", false));

        List<ApiKey> keys = apiKeyRepository.findAllByUserId(user.getId());

        assertThat(keys).hasSize(2);
        assertThat(keys).extracting(ApiKey::getKeyHash)
                .containsExactlyInAnyOrder("hash-all-active", "hash-all-inactive");
    }

    @Test
    void findAllByUserId_noKeys_returnsEmpty() {
        assertThat(apiKeyRepository.findAllByUserId(user.getId())).isEmpty();
    }

    @Test
    void findAllByUserId_unknownUser_returnsEmpty() {
        assertThat(apiKeyRepository.findAllByUserId(UUID.randomUUID())).isEmpty();
    }
}
