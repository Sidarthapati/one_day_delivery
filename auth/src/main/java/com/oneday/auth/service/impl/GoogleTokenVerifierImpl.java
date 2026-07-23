package com.oneday.auth.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.oneday.auth.exception.GoogleAuthNotConfiguredException;
import com.oneday.auth.exception.InvalidGoogleTokenException;
import com.oneday.auth.service.GoogleTokenVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * Real Google verifier. {@link GoogleIdTokenVerifier} checks the RS256 signature against Google's
 * published certs (cached), the issuer (accounts.google.com), the audience (our client id) and
 * expiry. If no client id is configured the endpoint reports "not configured" (503) rather than
 * silently accepting tokens.
 */
@Component
class GoogleTokenVerifierImpl implements GoogleTokenVerifier {

    private final GoogleIdTokenVerifier verifier; // null when not configured

    GoogleTokenVerifierImpl(@Value("${google.oauth.client-id:}") String clientId) {
        if (clientId == null || clientId.isBlank()) {
            this.verifier = null;
        } else {
            this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(clientId))
                    .build();
        }
    }

    @Override
    public GoogleUser verify(String idToken) {
        if (verifier == null) {
            throw new GoogleAuthNotConfiguredException();
        }
        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException e) {
            throw new InvalidGoogleTokenException("Could not verify Google token");
        }
        if (token == null) {
            throw new InvalidGoogleTokenException("Google token failed verification");
        }
        GoogleIdToken.Payload payload = token.getPayload();
        if (payload.getEmailVerified() == null || !payload.getEmailVerified()) {
            throw new InvalidGoogleTokenException("Google email is not verified");
        }
        return new GoogleUser(payload.getEmail(), payload.getSubject(), (String) payload.get("name"));
    }
}
