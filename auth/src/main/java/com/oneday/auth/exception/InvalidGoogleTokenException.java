package com.oneday.auth.exception;

/** The supplied Google ID token could not be verified (bad signature, audience, expiry, etc.). */
public class InvalidGoogleTokenException extends RuntimeException {
    public InvalidGoogleTokenException(String message) {
        super(message);
    }
}
