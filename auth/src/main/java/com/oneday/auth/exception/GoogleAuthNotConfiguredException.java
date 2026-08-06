package com.oneday.auth.exception;

/** Google sign-in was called but no google.oauth.client-id is configured on this environment. */
public class GoogleAuthNotConfiguredException extends RuntimeException {
    public GoogleAuthNotConfiguredException() {
        super("Google sign-in is not configured on this server");
    }
}
