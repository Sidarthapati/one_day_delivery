package com.oneday.auth.security;

import com.oneday.auth.domain.ApiKey;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private AuthService authService;
    @Mock private ApiKeyRepository apiKeyRepository;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(authService, apiKeyRepository);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── No headers ────────────────────────────────────────────────────────────

    @Test
    void noHeaders_chainContinues_noAuthSet() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(authService, apiKeyRepository);
    }

    // ── X-Api-Key paths ───────────────────────────────────────────────────────

    @Test
    void apiKey_validAndActiveUser_setsAuthentication() throws Exception {
        User user = buildUser(true);
        ApiKey apiKey = buildApiKey(user);
        when(apiKeyRepository.findActiveByKeyHashWithUser(anyString())).thenReturn(Optional.of(apiKey));
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(apiKey);

        request.addHeader("X-Api-Key", "raw-api-key");
        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(((AuthUserDetails) auth.getPrincipal()).getUsername()).isEqualTo("test@oneday.in");
    }

    @Test
    void apiKey_userInactive_noAuthSet() throws Exception {
        User inactiveUser = buildUser(false);
        ApiKey apiKey = buildApiKey(inactiveUser);
        when(apiKeyRepository.findActiveByKeyHashWithUser(anyString())).thenReturn(Optional.of(apiKey));

        request.addHeader("X-Api-Key", "raw-api-key");
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void apiKey_notFound_noAuthSet() throws Exception {
        when(apiKeyRepository.findActiveByKeyHashWithUser(anyString())).thenReturn(Optional.empty());

        request.addHeader("X-Api-Key", "unknown-key");
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void apiKey_repositoryThrows_exceptionSwallowed_chainStillContinues() throws Exception {
        when(apiKeyRepository.findActiveByKeyHashWithUser(anyString())).thenThrow(new RuntimeException("DB down"));

        request.addHeader("X-Api-Key", "raw-api-key");
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    // ── Bearer JWT paths ──────────────────────────────────────────────────────

    @Test
    void jwt_validToken_setsAuthentication() throws Exception {
        User user = buildUser(true);
        when(authService.validateToken("valid.jwt.token")).thenReturn(user);

        request.addHeader("Authorization", "Bearer valid.jwt.token");
        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(((AuthUserDetails) auth.getPrincipal()).getUsername()).isEqualTo("test@oneday.in");
    }

    @Test
    void jwt_validateTokenThrows_exceptionSwallowed_noAuthSet() throws Exception {
        when(authService.validateToken(anyString())).thenThrow(new RuntimeException("bad token"));

        request.addHeader("Authorization", "Bearer bad.token");
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void authorizationHeader_notBearer_skipsJwtAuth_noAuthSet() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(authService);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User buildUser(boolean active) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        role.setName("ADMIN");
        role.setDisplayName("Admin");
        role.setCityScoped(false);
        role.setBuiltin(true);
        role.setActive(true);

        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail("test@oneday.in");
        user.setPasswordHash("$2a$hash");
        user.setName("Test User");
        user.setRole(role);
        user.setActive(active);
        return user;
    }

    private ApiKey buildApiKey(User owner) {
        ApiKey key = new ApiKey();
        ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
        key.setKeyHash("hashed-key");
        key.setUser(owner);
        key.setLabel("test-key");
        key.setActive(true);
        return key;
    }
}
