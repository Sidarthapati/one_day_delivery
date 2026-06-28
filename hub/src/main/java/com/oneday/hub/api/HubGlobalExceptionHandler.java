package com.oneday.hub.api;

import com.oneday.hub.service.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps M7 service exceptions to RFC-7807 ProblemDetail responses (mirrors the orders handler). */
@RestControllerAdvice(basePackages = "com.oneday.hub")
class HubGlobalExceptionHandler {

    @ExceptionHandler({ParcelNotFoundException.class, BagNotFoundException.class, StandNotFoundException.class})
    ProblemDetail handleNotFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not found", ex);
    }

    @ExceptionHandler({NoFreeStandException.class, IllegalBagStateException.class, DuplicateBagItemException.class})
    ProblemDetail handleConflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex);
    }

    @ExceptionHandler(UnsupportedArrivalModeException.class)
    ProblemDetail handleUnsupported(UnsupportedArrivalModeException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Unsupported request", ex);
    }

    @ExceptionHandler(UndeterminedArrivalException.class)
    ProblemDetail handleUndeterminedArrival(UndeterminedArrivalException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex);
    }

    private ProblemDetail problem(HttpStatus status, String title, RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(title);
        pd.setDetail(ex.getMessage());
        return pd;
    }
}
