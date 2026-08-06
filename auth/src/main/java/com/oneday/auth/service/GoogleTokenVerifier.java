package com.oneday.auth.service;

/**
 * Verifies a Google OIDC ID token (signature against Google's public keys, audience, issuer,
 * expiry) and returns the trusted identity. Behind an interface so the auth flow is unit-testable
 * without hitting Google.
 */
public interface GoogleTokenVerifier {

    /** @throws com.oneday.auth.exception.InvalidGoogleTokenException if the token is invalid/unverified. */
    GoogleUser verify(String idToken);

    /** Trusted claims extracted from a verified Google ID token. */
    record GoogleUser(String email, String subject, String name) {}
}
