package com.oneday.auth.exception;

/** Object storage (R2) is not configured/available — presign and upload cannot proceed (→ 503). */
public class StorageUnavailableException extends RuntimeException {
    public StorageUnavailableException(String message) {
        super(message);
    }
}
