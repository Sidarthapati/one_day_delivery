package com.oneday.auth.exception;

/** Presented refresh token is unknown, expired, or already revoked (reuse). Maps to 401. */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
