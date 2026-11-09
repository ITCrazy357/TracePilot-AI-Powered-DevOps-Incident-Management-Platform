package com.tracepilot.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import com.tracepilot.infrastructure.security.RateLimitExceeded;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RateLimitExceeded.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitExceeded failure) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", Long.toString(failure.retryAfterSeconds()))
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, failure.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ProblemDetail storageUnavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
            "Storage is temporarily unavailable.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        // Never expose SQL, constraint details, or submitted credentials.
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "The requested record conflicts with existing data.");
    }
}
