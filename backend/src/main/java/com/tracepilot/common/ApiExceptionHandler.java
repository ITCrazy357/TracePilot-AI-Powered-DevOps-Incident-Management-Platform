package com.tracepilot.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        // Never expose SQL, constraint details, or submitted credentials.
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "The requested record conflicts with existing data.");
    }
}
