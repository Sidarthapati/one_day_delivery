package com.oneday.auth.exception;

public class ApiKeyCapExceededException extends RuntimeException {
    public ApiKeyCapExceededException() {
        super("API key limit reached (max 10 active keys per user)");
    }
}
