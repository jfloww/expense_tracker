package com.expensetracker.shared.error;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    record ErrorResponse(String code) {}

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ErrorResponse> domainError(DomainException exception) {
        int status = switch (exception.kind()) {
            case NOT_FOUND -> 404;
            case FORBIDDEN -> 403;
            case INVALID -> 400;
            case CONFLICT -> 409;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> constraintError() {
        return ResponseEntity.status(409).body(new ErrorResponse("constraint_violation"));
    }
}
