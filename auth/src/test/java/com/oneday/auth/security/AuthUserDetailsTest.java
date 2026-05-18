package com.oneday.auth.security;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthUserDetailsTest {

    private UUID userId;
    private User user;
    private AuthUserDetails authUserDetails;

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.setName("ADMIN");

        userId = UUID.randomUUID();
        user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("test@oneday.in");
        user.setPasswordHash("$2a$hashed");
        user.setName("Test User");
        user.setRole(role);
        user.setActive(true);

        authUserDetails = new AuthUserDetails(user);
    }

    @Test
    void getUsername_returnsEmail() {
        assertThat(authUserDetails.getUsername()).isEqualTo("test@oneday.in");
    }

    @Test
    void getPassword_returnsPasswordHash() {
        assertThat(authUserDetails.getPassword()).isEqualTo("$2a$hashed");
    }

    @Test
    void getAuthorities_returnsPrefixedRoleName() {
        assertThat(authUserDetails.getAuthorities())
                .hasSize(1)
                .first()
                .satisfies(a -> assertThat(a.getAuthority()).isEqualTo("ROLE_ADMIN"));
    }

    @Test
    void getUserId_returnsUserId() {
        assertThat(authUserDetails.getUserId()).isEqualTo(userId);
    }

    @Test
    void getUser_returnsUnderlyingUser() {
        assertThat(authUserDetails.getUser()).isSameAs(user);
    }

    @Test
    void isEnabled_activeUser_returnsTrue() {
        assertThat(authUserDetails.isEnabled()).isTrue();
    }

    @Test
    void isEnabled_inactiveUser_returnsFalse() {
        user.setActive(false);
        assertThat(authUserDetails.isEnabled()).isFalse();
    }

    @Test
    void isAccountNonExpired_alwaysTrue() {
        assertThat(authUserDetails.isAccountNonExpired()).isTrue();
    }

    @Test
    void isAccountNonLocked_alwaysTrue() {
        assertThat(authUserDetails.isAccountNonLocked()).isTrue();
    }

    @Test
    void isCredentialsNonExpired_alwaysTrue() {
        assertThat(authUserDetails.isCredentialsNonExpired()).isTrue();
    }
}
