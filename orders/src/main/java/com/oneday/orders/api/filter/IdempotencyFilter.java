package com.oneday.orders.api.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.config.IdempotencyProperties;
import com.oneday.orders.domain.IdempotencyKey;
import com.oneday.orders.domain.IdempotencyKeyId;
import com.oneday.orders.repository.IdempotencyKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
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
 *       comes back with a non-5xx status, the key + SHA-256 canonical body fingerprint +
 *       response are stored in {@code idempotency_keys}.</li>
 *   <li>Repeat request with matching body (hit, within TTL): the cached response is returned
 *       immediately with {@code Idempotency-Replayed: true} header. The handler is not invoked.</li>
 *   <li>Repeat request with different body (mismatch): {@code 422 Unprocessable Entity}
 *       with error code {@code IDEMPOTENCY_KEY_BODY_MISMATCH}.</li>
 *   <li>Repeat request with the same key after TTL expiry: treated as a new miss.</li>
 * </ol>
 *
 * <h2>Fingerprint</h2>
 * The body fingerprint is SHA-256 of the <em>canonicalised</em> JSON body (all object keys
 * sorted alphabetically, whitespace stripped). This makes the fingerprint order-independent:
 * {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}} produce the same fingerprint. If the body
 * is not valid JSON, the fingerprint falls back to SHA-256 of the raw bytes.
 *
 * <h2>Body availability</h2>
 * The filter reads the request body once into a byte array before fingerprinting, then wraps
 * the request in a {@link ReplayableRequestWrapper} that re-serves those bytes on every
 * {@link jakarta.servlet.http.HttpServletRequest#getInputStream()} call. This ensures
 * {@code @RequestBody} deserialization by the downstream Spring MVC handler is unaffected.
 *
 * <h2>Scoping</h2>
 * Keys are scoped to {@code (key, user_id)} — two users with the same key string are
 * independent. The {@code user_id} is extracted from the Spring Security
 * {@link AuthUserDetails} principal set by M1's JWT filter.
 *
 * <h2>TTL</h2>
 * Keys expire after {@link IdempotencyProperties#getTtl()} (default 24 h). Expiry is
 * checked at hit time so clients always see the correct replay/miss behaviour regardless
 * of when the nightly purge job last ran.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    static final String HEADER_IDEMPOTENCY_KEY      = "Idempotency-Key";
    static final String HEADER_IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";
    /** Aligned with M4-ORDERS-DESIGN.md §4 KDD-6 and DECISIONS.md M4-D-016. */
    static final String ERROR_CODE_MISSING_HEADER   = "IDEMPOTENCY_KEY_REQUIRED";
    static final String ERROR_CODE_BODY_MISMATCH    = "IDEMPOTENCY_KEY_BODY_MISMATCH";
    static final String ERROR_CODE_KEY_TOO_LONG     = "IDEMPOTENCY_KEY_TOO_LONG";

    /** Maximum allowed length for the Idempotency-Key header value (matches DB column). */
    private static final int MAX_KEY_LENGTH = 100;

    /**
     * ObjectMapper configured to write JSON with keys sorted alphabetically.
     * Used to produce the canonical form for fingerprinting.
     * {@link ObjectMapper} is thread-safe after configuration and safe to share.
     */
    /** Shared mapper for reading and writing canonical JSON. Thread-safe after construction. */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    private final IdempotencyKeyRepository repository;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public IdempotencyFilter(IdempotencyKeyRepository repository,
                             IdempotencyProperties properties,
                             ObjectMapper objectMapper) {
        this.repository   = repository;
        this.properties   = properties;
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
        return !pathMatcher.match(properties.getApplyToPathPattern(), request.getRequestURI());
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

        // 2. Header length guard (IdempotencyKeyId.key is VARCHAR(100))
        if (key.length() > MAX_KEY_LENGTH) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_CODE_KEY_TOO_LONG,
                    "Idempotency-Key must be " + MAX_KEY_LENGTH + " characters or fewer");
            return;
        }

        // 3. Extract authenticated user id
        UUID userId = extractUserId();
        if (userId == null) {
            // Should not happen — JWT filter runs first; but be defensive
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHENTICATED", "Authentication required");
            return;
        }

        // 4. Read the request body ONCE into a byte array.
        //    We must do this before wrapping so the raw ServletInputStream is only consumed once.
        //    The ReplayableRequestWrapper will re-serve these bytes for the downstream handler.
        byte[] bodyBytes = request.getInputStream().readAllBytes();

        // 5. Compute canonical JSON fingerprint (keys sorted, whitespace stripped).
        //    Falls back to raw bytes for non-JSON bodies.
        String incomingFingerprint = sha256HexCanonical(bodyBytes);

        // 6. Wrap request so the handler can still read the body (@RequestBody deserialization).
        ReplayableRequestWrapper replayableRequest = new ReplayableRequestWrapper(request, bodyBytes);

        // 7. Wrap response to capture the body for caching.
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        IdempotencyKeyId keyId = new IdempotencyKeyId(key, userId);

        // 8. DB lookup
        Optional<IdempotencyKey> existing = repository.findById(keyId);

        if (existing.isPresent()) {
            IdempotencyKey cached = existing.get();

            // Check TTL at hit time — the nightly purge runs only once a day, so a
            // record that expired hours ago could still be in the table.
            if (!cached.getExpiresAt().isBefore(Instant.now())) {

                // Mismatch: same key, different canonical body
                if (cached.getRequestFingerprint() != null
                        && !cached.getRequestFingerprint().equals(incomingFingerprint)) {
                    writeError(wrappedResponse, 422,
                            ERROR_CODE_BODY_MISMATCH,
                            "A different request body was previously sent with this Idempotency-Key");
                    wrappedResponse.copyBodyToResponse();
                    return;
                }

                // Hit: replay cached response
                log.debug("Idempotency hit — replaying key={}**** userId={}",
                        key.substring(0, Math.min(4, key.length())), userId);
                wrappedResponse.setStatus(cached.getResponseStatus());
                wrappedResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                wrappedResponse.addHeader(HEADER_IDEMPOTENCY_REPLAYED, "true");
                wrappedResponse.getWriter().write(cached.getResponseBody());
                wrappedResponse.copyBodyToResponse();
                return;
            }
            // Key is present but expired — fall through and treat as a miss
        }

        // 9. Miss — let the handler run
        chain.doFilter(replayableRequest, wrappedResponse);

        // 10. Persist only if the response is not a server error (5xx)
        int status = wrappedResponse.getStatus();
        if (status < 500) {
            String responseBody = new String(wrappedResponse.getContentAsByteArray(),
                    StandardCharsets.UTF_8);
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
                log.warn("Idempotency key already persisted by concurrent request: key={}**** userId={}",
                        key.substring(0, Math.min(4, key.length())), userId);
            }
        }

        // 11. Always copy the real response body back to the client
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
     * Computes SHA-256 of the <em>canonicalised</em> JSON body and returns it as a
     * 64-character lowercase hex string.
     *
     * <p>Canonicalisation: all JSON object keys are sorted alphabetically at every level,
     * and whitespace is stripped (compact serialisation). This makes the fingerprint
     * order-independent across client implementations:
     * {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}} produce the same fingerprint.</p>
     *
     * <p>Falls back to {@link #sha256Hex(byte[])} of the raw bytes if the body is empty
     * or not valid JSON.</p>
     */
    static String sha256HexCanonical(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return sha256Hex(bytes != null ? bytes : new byte[0]);
        }
        try {
            JsonNode tree = CANONICAL_MAPPER.readTree(bytes);
            JsonNode sorted = sortKeysRecursively(tree);
            byte[] canonical = CANONICAL_MAPPER.writeValueAsBytes(sorted);
            return sha256Hex(canonical);
        } catch (Exception e) {
            // Non-JSON body (e.g., form-encoded or binary) — fall back to raw bytes
            return sha256Hex(bytes);
        }
    }

    /**
     * Recursively sorts all object keys in a {@link JsonNode} alphabetically.
     * Array elements and scalar nodes are returned unchanged; only {@code ObjectNode}
     * children are reordered.
     */
    private static JsonNode sortKeysRecursively(JsonNode node) {
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String key : keys) {
                sorted.set(key, sortKeysRecursively(node.get(key)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                array.add(sortKeysRecursively(element));
            }
            return array;
        }
        return node;
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

    // -------------------------------------------------------------------------
    // Inner helpers
    // -------------------------------------------------------------------------

    /**
     * Wraps an {@link HttpServletRequest} and replays the given pre-read body bytes on every
     * call to {@link #getInputStream()} or {@link #getReader()}.
     *
     * <p>This is necessary because the filter reads the raw {@link jakarta.servlet.ServletInputStream}
     * to compute the fingerprint before the downstream handler runs. Without this wrapper,
     * Spring MVC's {@code @RequestBody} deserialiser would see an exhausted stream and produce
     * an empty or null DTO.</p>
     */
    private static class ReplayableRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] bodyBytes;

        ReplayableRequestWrapper(HttpServletRequest request, byte[] bodyBytes) {
            super(request);
            this.bodyBytes = bodyBytes;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(bodyBytes);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(bodyBytes), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return bodyBytes.length;
        }

        @Override
        public long getContentLengthLong() {
            return bodyBytes.length;
        }
    }

    /**
     * Adapts a {@link ByteArrayInputStream} to the {@link ServletInputStream} contract.
     * Each instance is created fresh per request (not shared), so thread safety is inherent.
     */
    private static class ByteArrayServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream bais;

        ByteArrayServletInputStream(byte[] bytes) {
            this.bais = new ByteArrayInputStream(bytes);
        }

        @Override public int read()                                  { return bais.read(); }
        @Override public int read(byte[] b, int off, int len)        { return bais.read(b, off, len); }
        @Override public boolean isFinished()                        { return bais.available() == 0; }
        @Override public boolean isReady()                           { return true; }
        @Override public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("Async not supported by IdempotencyFilter");
        }
    }
}
