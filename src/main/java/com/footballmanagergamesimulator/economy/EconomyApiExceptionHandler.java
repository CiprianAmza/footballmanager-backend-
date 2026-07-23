package com.footballmanagergamesimulator.economy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@RestControllerAdvice(assignableTypes = PersonalEconomyController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EconomyApiExceptionHandler {

    @ExceptionHandler(EconomyConflictException.class)
    public ResponseEntity<EconomyDtos.ApiError> conflict(EconomyConflictException exception) {
        HttpStatus status = exception.getCode().endsWith("NOT_FOUND")
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(new EconomyDtos.ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<EconomyDtos.ApiError> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new EconomyDtos.ApiError("INVALID_REQUEST", exception.getMessage()));
    }
}
