package com.oneday.airline.api;

import com.oneday.airline.service.exception.AwbNotFoundException;
import com.oneday.airline.service.exception.ConsolidatorLegNotFoundException;
import com.oneday.airline.service.exception.ConsolidatorRateNotFoundException;
import com.oneday.airline.service.exception.NoFlightAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Uniform error envelope for the M9 airline API (RFC-7807 ProblemDetail). Maps the module's domain
 * exceptions to proper statuses; extends Spring's base so standard MVC exceptions keep their status;
 * the safe catch-all logs the cause and returns a generic body (no internal leak).
 */
@RestControllerAdvice(basePackages = "com.oneday.airline")
class AirlineExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AirlineExceptionHandler.class);

    @ExceptionHandler({AwbNotFoundException.class, ConsolidatorLegNotFoundException.class,
            ConsolidatorRateNotFoundException.class})
    ProblemDetail handleNotFound(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Not found");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(NoFlightAvailableException.class)
    ProblemDetail handleNoFlight(NoFlightAvailableException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("No flight available");
        pd.setDetail(ex.getMessage());
        return pd;
    }

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
        log.error("Unhandled exception in airline API", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal error");
        pd.setDetail("An unexpected error occurred. Please try again later.");
        return pd;
    }
}
