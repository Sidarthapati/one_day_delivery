package com.oneday.auth.exception;

public class UnknownCityException extends RuntimeException {
    public UnknownCityException(String cityId) {
        super("Unknown city for DA registration: " + cityId);
    }
}
