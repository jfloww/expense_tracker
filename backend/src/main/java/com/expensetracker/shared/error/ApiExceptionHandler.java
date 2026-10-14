package com.expensetracker.shared.error;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;

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
            case UNAUTHENTICATED -> 401;
            case RATE_LIMITED -> 429;
        };
        var response = ResponseEntity.status(status).header("Cache-Control", "private, no-store");
        if (exception.kind() == DomainException.Kind.RATE_LIMITED) {
            response.header("Retry-After", Integer.toString(exception.retryAfterSeconds()));
        }
        return response.body(new ErrorResponse(exception.code()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> constraintError() {
        return ResponseEntity.status(409).body(new ErrorResponse("constraint_violation"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> invalidInput() {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request"));
    }
}
