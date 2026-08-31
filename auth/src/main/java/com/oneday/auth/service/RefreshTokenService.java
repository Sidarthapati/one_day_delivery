package com.oneday.auth.service;

import com.oneday.auth.domain.User;

import java.time.Instant;

/**
 * Lifecycle of rotating refresh tokens: mint on login, rotate on use, revoke on logout, and detect
 * reuse of an already-rotated token (theft) by revoking the whole family. The access JWT is minted
 * by {@link JwtService}; this service owns only the stateful refresh half.
 */
public interface RefreshTokenService {

    /** Mint a new refresh token in a fresh family for a just-authenticated user. */
    Issued issue(User user);

    /**
     * Mint a refresh token bound to {@code deviceId} (X-Device-Id). Enables single-active-device
     * enforcement and lets ops see which physical device holds a session. Null deviceId = unbound.
     */
    Issued issue(User user, String deviceId);

    /**
     * Revoke this user's still-active sessions on any device other than {@code deviceId} (the
     * single-active-device sweep). No-op when deviceId is null. Returns the number revoked.
     */
    int revokeOtherDevices(User user, String deviceId);

    /**
     * Validate + rotate a presented raw refresh token: revoke it and mint its successor in the same
     * family. Reuse of an already-revoked token revokes the entire family and throws.
     */
    Rotation rotate(String rawToken);

    /** Revoke a presented raw refresh token (logout). Idempotent — unknown/already-revoked is a no-op. */
    void revoke(String rawToken);

    /** The raw token is returned to the client exactly once; only its hash is stored. */
    record Issued(String rawToken, Instant expiresAt) {}

    /** Result of a successful rotation: the owning user plus the freshly minted refresh token. */
    record Rotation(User user, String rawToken, Instant expiresAt) {}
}
