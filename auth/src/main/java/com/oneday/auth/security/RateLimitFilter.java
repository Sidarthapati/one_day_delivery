package com.oneday.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-instance in-memory rate limiter for the abuse-prone auth surface (login / OTP / register) and
 * API-key traffic. Runs before authentication so brute-force is throttled before any auth work.
 * Disabled by default ({@code godspeed.ratelimit.enabled=false}) — active only where configured
 * (prod). Over-limit → {@code 429} + {@code Retry-After}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long STALE_MILLIS = 10 * 60 * 1000L; // evict buckets idle > 10 min
    private static final int PRUNE_EVERY = 1000;

    private final RateLimitProperties props;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger sincePrune = new AtomicInteger();

    public RateLimitFilter(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        Target target = targetFor(request);
        if (target == null) {
            chain.doFilter(request, response);
            return;
        }

        TokenBucket bucket = buckets.computeIfAbsent(
                target.key,
                k -> new TokenBucket(target.rule.getCapacity(), target.rule.getWindow().toNanos()));

        if (!bucket.tryConsume()) {
            response.setStatus(429); // 429 Too Many Requests
            response.setHeader("Retry-After", String.valueOf(bucket.retryAfterSeconds()));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"rate_limited\",\"message\":\"Too many requests. Please retry later.\"}");
            return;
        }

        maybePrune();
        chain.doFilter(request, response);
    }

    private Target targetFor(HttpServletRequest request) {
        // API-key traffic: throttle per key regardless of path.
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return new Target("api:" + sha256Hex(apiKey), props.getApiKey());
        }

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        String ip = clientIp(request);
        return switch (path) {
            case "/auth/login" -> new Target("login:" + ip, props.getLogin());
            case "/auth/otp/request" -> new Target("otp:" + ip, props.getOtp());
            case "/auth/register", "/auth/request-onboarding", "/auth/request-business-onboarding" ->
                    new Target("register:" + ip, props.getRegister());
            default -> null;
        };
    }

    // Behind Render/Vercel the real client IP is the first X-Forwarded-For entry.
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    private void maybePrune() {
        if (sincePrune.incrementAndGet() < PRUNE_EVERY) {
            return;
        }
        sincePrune.set(0);
        long cutoff = System.currentTimeMillis() - STALE_MILLIS;
        buckets.entrySet().removeIf(e -> e.getValue().lastAccessMillis() < cutoff);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private record Target(String key, RateLimitProperties.Rule rule) {
    }
}
