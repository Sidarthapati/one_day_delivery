package com.oneday.orders.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.auth.domain.User;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.config.IdempotencyProperties;
import com.oneday.orders.domain.IdempotencyKey;
import com.oneday.orders.domain.IdempotencyKeyId;
import com.oneday.orders.repository.IdempotencyKeyRepository;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.oneday.orders.api.filter.IdempotencyFilter.ERROR_CODE_BODY_MISMATCH;
import static com.oneday.orders.api.filter.IdempotencyFilter.ERROR_CODE_MISSING_HEADER;
import static com.oneday.orders.api.filter.IdempotencyFilter.HEADER_IDEMPOTENCY_KEY;
import static com.oneday.orders.api.filter.IdempotencyFilter.HEADER_IDEMPOTENCY_REPLAYED;
import static com.oneday.orders.api.filter.IdempotencyFilter.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyFilter")
class IdempotencyFilterTest {

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyFilter filter;
    private ObjectMapper objectMapper;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String IDEMPOTENCY_KEY = "test-key-abc-123";
    private static final String REQUEST_BODY = "{\"weight\":500,\"originCity\":\"BOM\"}";
    private static final String CACHED_BODY = "{\"shipmentRef\":\"1DD-BOM-20260529-00001\"}";

    @BeforeEach
    void setUp() {
        IdempotencyProperties props = new IdempotencyProperties();
        props.setTtl(Duration.ofHours(24));
        props.setApplyToPathPattern("/api/v1/**");

        objectMapper = new ObjectMapper();
        filter = new IdempotencyFilter(repository, props, objectMapper);

        // Put an authenticated user into the SecurityContext.
        // Use a real User instance — mock(User.class) fails on Java 21 because
        // ByteBuddy cannot instrument the BaseEntity class hierarchy.
        User user = new User();
        ReflectionTestUtils.setField(user, "id", USER_ID); // sets BaseEntity.id
        AuthUserDetails principal = new AuthUserDetails(user);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // shouldNotFilter
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("shouldNotFilter")
    class ShouldNotFilter {

        @Test
        @DisplayName("GET request is skipped entirely")
        void getRequest_isSkipped() throws Exception {
            MockHttpServletRequest request = postRequest();
            request.setMethod("GET");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            // chain was advanced — filter did not intercept
            assertThat(chain.getRequest()).isNotNull();
            verify(repository, never()).findById(any());
        }

        @Test
        @DisplayName("POST to non-matching path is skipped")
        void postToNonMatchingPath_isSkipped() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
            request.setContent(REQUEST_BODY.getBytes());
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.getRequest()).isNotNull();
            verify(repository, never()).findById(any());
        }
    }

    // -------------------------------------------------------------------------
    // Missing header
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST without Idempotency-Key header → 400")
    void missingHeader_returns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shipments");
        request.setContent(REQUEST_BODY.getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains(ERROR_CODE_MISSING_HEADER);
        // chain must NOT have been advanced
        assertThat(chain.getRequest()).isNull();
    }

    // -------------------------------------------------------------------------
    // Miss path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("miss — key not in DB → handler runs, 2xx response is persisted")
    void miss_handlerRunsAndResponsePersisted() throws Exception {
        when(repository.findById(any())).thenReturn(Optional.empty());

        MockHttpServletRequest request = postRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new RespondingServlet(201, CACHED_BODY));

        filter.doFilter(request, response, chain);

        // Handler was invoked (chain advanced to servlet)
        assertThat(chain.getRequest()).isNotNull();

        // Response passed through to client
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).contains("shipmentRef");

        // IdempotencyKey was saved with correct fields
        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).save(captor.capture());

        IdempotencyKey saved = captor.getValue();
        assertThat(saved.getId().getKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(saved.getId().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getResponseStatus()).isEqualTo((short) 201);
        assertThat(saved.getResponseBody()).contains("shipmentRef");
        assertThat(saved.getRequestFingerprint()).isEqualTo(sha256Hex(REQUEST_BODY.getBytes()));
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("miss — 5xx response → NOT persisted, body still reaches client")
    void miss_serverError_notPersisted() throws Exception {
        when(repository.findById(any())).thenReturn(Optional.empty());

        MockHttpServletRequest request = postRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new RespondingServlet(500, "{\"error\":\"internal\"}"));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Hit — matching fingerprint (replay)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hit-match — same body → cached response replayed with Idempotency-Replayed header")
    void hitMatch_cachedResponseReplayed() throws Exception {
        String fingerprint = sha256Hex(REQUEST_BODY.getBytes());
        IdempotencyKey cached = cachedKey(fingerprint, (short) 201, CACHED_BODY);
        when(repository.findById(any())).thenReturn(Optional.of(cached));

        MockHttpServletRequest request = postRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // Handler was NOT invoked
        assertThat(chain.getRequest()).isNull();

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader(HEADER_IDEMPOTENCY_REPLAYED)).isEqualTo("true");
        assertThat(response.getContentAsString()).contains("shipmentRef");
        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Hit — mismatched fingerprint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hit-mismatch — different body → 422 IDEMPOTENCY_KEY_BODY_MISMATCH")
    void hitMismatch_returns422() throws Exception {
        String differentFingerprint = sha256Hex("completely-different-body".getBytes());
        IdempotencyKey cached = cachedKey(differentFingerprint, (short) 201, CACHED_BODY);
        when(repository.findById(any())).thenReturn(Optional.of(cached));

        MockHttpServletRequest request = postRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains(ERROR_CODE_BODY_MISMATCH);
        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // sha256Hex helper
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sha256Hex produces a 64-char lowercase hex string")
    void sha256Hex_produces64CharHex() {
        String hash = sha256Hex("hello world".getBytes());
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("sha256Hex is deterministic")
    void sha256Hex_isDeterministic() {
        byte[] input = "same input".getBytes();
        assertThat(sha256Hex(input)).isEqualTo(sha256Hex(input));
    }

    @Test
    @DisplayName("sha256Hex differs for different inputs")
    void sha256Hex_differsForDifferentInputs() {
        assertThat(sha256Hex("a".getBytes())).isNotEqualTo(sha256Hex("b".getBytes()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MockHttpServletRequest postRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shipments");
        request.addHeader(HEADER_IDEMPOTENCY_KEY, IDEMPOTENCY_KEY);
        request.setContent(REQUEST_BODY.getBytes());
        request.setContentType("application/json");
        return request;
    }

    private IdempotencyKey cachedKey(String fingerprint, short status, String body) {
        IdempotencyKey key = new IdempotencyKey();
        key.setId(new IdempotencyKeyId(IDEMPOTENCY_KEY, USER_ID));
        key.setRequestFingerprint(fingerprint);
        key.setResponseStatus(status);
        key.setResponseBody(body);
        key.setExpiresAt(Instant.now().plusSeconds(3600));
        return key;
    }

    /**
     * Minimal servlet that writes a fixed status + body to simulate a downstream handler.
     * Used only in MockFilterChain — not a Spring component.
     */
    @SuppressWarnings("serial")
    private static class RespondingServlet extends GenericServlet {
        private final int status;
        private final String body;

        RespondingServlet(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws IOException {
            HttpServletResponse httpRes = (HttpServletResponse) res;
            httpRes.setStatus(status);
            httpRes.setContentType("application/json");
            httpRes.getWriter().write(body);
        }
    }
}
