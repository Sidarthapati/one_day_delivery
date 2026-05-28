package com.oneday.auth.repository;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;

    private Role adminRole;

    @BeforeEach
    void setUp() {
        adminRole = roleRepository.findByName("ADMIN").orElseThrow();
    }

    private User buildUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hashed");
        u.setName("Test User");
        u.setRole(adminRole);
        u.setActive(true);
        return u;
    }

    // ── findByEmail ───────────────────────────────────────────────────────────

    @Test
    void findByEmail_found_returnsUser() {
        userRepository.save(buildUser("find@repo-test.com"));
        userRepository.flush();

        Optional<User> result = userRepository.findByEmail("find@repo-test.com");

        assertThat(result).isPresent();
        assertThat(result.get().getEmail()).isEqualTo("find@repo-test.com");
    }

    @Test
    void findByEmail_notFound_returnsEmpty() {
        assertThat(userRepository.findByEmail("nobody@repo-test.com")).isEmpty();
    }

    // ── existsByEmail ─────────────────────────────────────────────────────────

    @Test
    void existsByEmail_existingEmail_returnsTrue() {
        userRepository.save(buildUser("exists@repo-test.com"));
        userRepository.flush();

        assertThat(userRepository.existsByEmail("exists@repo-test.com")).isTrue();
    }

    @Test
    void existsByEmail_unknownEmail_returnsFalse() {
        assertThat(userRepository.existsByEmail("nope@repo-test.com")).isFalse();
    }

    // ── findActiveByIdWithRole ────────────────────────────────────────────────

    @Test
    void findActiveByIdWithRole_activeUser_returnsUserWithRole() {
        User saved = userRepository.saveAndFlush(buildUser("active@repo-test.com"));

        Optional<User> result = userRepository.findActiveByIdWithRole(saved.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
        assertThat(result.get().getRole().getName()).isEqualTo("ADMIN");
    }

    @Test
    void findActiveByIdWithRole_inactiveUser_returnsEmpty() {
        User u = buildUser("inactive@repo-test.com");
        u.setActive(false);
        User saved = userRepository.saveAndFlush(u);

        assertThat(userRepository.findActiveByIdWithRole(saved.getId())).isEmpty();
    }

    @Test
    void findActiveByIdWithRole_unknownId_returnsEmpty() {
        assertThat(userRepository.findActiveByIdWithRole(UUID.randomUUID())).isEmpty();
    }

    // ── findByIdWithPermissions ───────────────────────────────────────────────

    @Test
    void findByIdWithPermissions_found_loadsRoleAndPermissions() {
        User saved = userRepository.saveAndFlush(buildUser("perms@repo-test.com"));

        Optional<User> result = userRepository.findByIdWithPermissions(saved.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getRole()).isNotNull();
        // ADMIN has permissions seeded — collection is initialised (not a lazy proxy)
        assertThat(result.get().getRole().getPermissions()).isNotNull();
    }

    @Test
    void findByIdWithPermissions_unknownId_returnsEmpty() {
        assertThat(userRepository.findByIdWithPermissions(UUID.randomUUID())).isEmpty();
    }

    // ── existsByRoleId ────────────────────────────────────────────────────────

    @Test
    void existsByRoleId_roleInUse_returnsTrue() {
        userRepository.saveAndFlush(buildUser("rolecheck@repo-test.com"));

        assertThat(userRepository.existsByRoleId(adminRole.getId())).isTrue();
    }

    @Test
    void existsByRoleId_roleNotInUse_returnsFalse() {
        assertThat(userRepository.existsByRoleId(UUID.randomUUID())).isFalse();
    }
}
