package com.oneday.orders.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.config.IdempotencyProperties;
import com.oneday.orders.domain.IdempotencyKey;
import com.oneday.orders.domain.IdempotencyKeyId;
import com.oneday.orders.repository.IdempotencyKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Servlet filter that enforces idempotency for non-safe HTTP methods (POST).
 *
 * <h2>Protocol</h2>
 * <ol>
 *   <li>Client sends {@code Idempotency-Key: <uuid-or-any-string>} header on every POST.</li>
 *   <li>First request (miss): the request is passed to the handler. When the response
 *       comes back with a non-5xx status, the key + SHA-256 body fingerprint + response
 *       are stored in {@code idempotency_keys}.</li>
 *   <li>Repeat request with matching body (hit): the cached response is returned
 *       immediately with {@code Idempotency-Replayed: true} header. The handler is
 *       not invoked.</li>
 *   <li>Repeat request with different body (mismatch): {@code 422 Unprocessable Entity}
 *       with error code {@code IDEMPOTENCY_KEY_BODY_MISMATCH}.</li>
 * </ol>
 *
 * <h2>Scoping</h2>
 * Keys are scoped to {@code (key, user_id)} — two users with the same key string
 * are independent. The {@code user_id} is extracted from the Spring Security
 * {@link AuthUserDetails} principal set by M1's JWT filter.
 *
 * <h2>TTL</h2>
 * Keys expire after {@link IdempotencyProperties#getTtl()} (default 24 h) and are
 * purged nightly by {@link com.oneday.orders.service.IdempotencyKeyPurgeJob}.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String HEADER_IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";
    static final String ERROR_CODE_MISSING_HEADER = "IDEMPOTENCY_KEY_MISSING";
    static final String ERROR_CODE_BODY_MISMATCH = "IDEMPOTENCY_KEY_BODY_MISMATCH";

    private final IdempotencyKeyRepository repository;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public IdempotencyFilter(IdempotencyKeyRepository repository,
                             IdempotencyProperties properties,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Skip the filter for:
     * <ul>
     *   <li>Non-POST methods (GET, PUT, DELETE are naturally idempotent or handled differently)</li>
     *   <li>Paths that do not match {@link IdempotencyProperties#getApplyToPathPattern()}</li>
     * </ul>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !pathMatcher.match(properties.getApplyToPathPattern(), path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // 1. Header presence check
        String key = request.getHeader(HEADER_IDEMPOTENCY_KEY);
        if (key == null || key.isBlank()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_CODE_MISSING_HEADER,
                    "Idempotency-Key header is required for POST requests");
            return;
        }

        // 2. Extract authenticated user id
        UUID userId = extractUserId();
        if (userId == null) {
            // Should not happen — JWT filter runs first; but be defensive
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        IdempotencyKeyId keyId = new IdempotencyKeyId(key, userId);

        // 3. Wrap request/response so the body can be read multiple times
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        // 4. Eagerly read the body so the fingerprint is available before the chain runs
        //    (ContentCachingRequestWrapper caches on first getInputStream() / getReader() call)
        wrappedRequest.getInputStream().readAllBytes();

        String incomingFingerprint = sha256Hex(wrappedRequest.getContentAsByteArray());

        // 5. DB lookup
        Optional<IdempotencyKey> existing = repository.findById(keyId);

        if (existing.isPresent()) {
            IdempotencyKey cached = existing.get();

            // Mismatch: same key, different body
            if (cached.getRequestFingerprint() != null
                    && !cached.getRequestFingerprint().equals(incomingFingerprint)) {
                writeError(wrappedResponse, 422,
                        ERROR_CODE_BODY_MISMATCH,
                        "A different request body was previously sent with this Idempotency-Key");
                wrappedResponse.copyBodyToResponse();
                return;
            }

            // Hit: replay cached response
            log.debug("Idempotency hit — replaying key={} userId={}", key, userId);
            wrappedResponse.setStatus(cached.getResponseStatus());
            wrappedResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            wrappedResponse.addHeader(HEADER_IDEMPOTENCY_REPLAYED, "true");
            wrappedResponse.getWriter().write(cached.getResponseBody());
            wrappedResponse.copyBodyToResponse();
            return;
        }

        // 6. Miss — let the handler run
        chain.doFilter(wrappedRequest, wrappedResponse);

        // 7. Persist only if the response is not a server error (5xx)
        int status = wrappedResponse.getStatus();
        if (status < 500) {
            String responseBody = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
            IdempotencyKey record = new IdempotencyKey();
            record.setId(keyId);
            record.setRequestFingerprint(incomingFingerprint);
            record.setResponseStatus((short) status);
            record.setResponseBody(responseBody);
            record.setExpiresAt(Instant.now().plus(properties.getTtl()));
            try {
                repository.save(record);
            } catch (Exception e) {
                // Concurrent duplicate insert — the unique PK constraint will reject one;
                // the winning thread already persisted the record. Log and continue.
                log.warn("Idempotency key already persisted by concurrent request: key={} userId={}", key, userId);
            }
        }

        // 8. Always copy the real response body back to the client
        wrappedResponse.copyBodyToResponse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    /**
     * Computes SHA-256 of {@code bytes} and returns it as a 64-character lowercase hex string.
     */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — never thrown
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void writeError(HttpServletResponse response, int status,
                            String errorCode, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = objectMapper.writeValueAsString(
                Map.of("error", errorCode, "message", message));
        response.getWriter().write(body);
    }
}
