package com.oneday.barcode.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Uniform error envelope for the barcode API (RFC-7807 ProblemDetail). Extends Spring's base so all
 * standard MVC exceptions keep their correct status; adds a bad-request mapping and a safe
 * catch-all that logs the real cause server-side and returns a generic body (no internal leak).
 */
@RestControllerAdvice(basePackages = "com.oneday.barcode")
class BarcodeExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BarcodeExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Bad request");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    Object handleUnexpected(Exception ex) throws Exception {
        // Let Spring Security map its own auth/authz exceptions (401/403) — never mask them as 500.
        if (ex.getClass().getName().startsWith("org.springframework.security")) {
            throw ex;
        }
        log.error("Unhandled exception in barcode API", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal error");
        pd.setDetail("An unexpected error occurred. Please try again later.");
        return pd;
    }
}
