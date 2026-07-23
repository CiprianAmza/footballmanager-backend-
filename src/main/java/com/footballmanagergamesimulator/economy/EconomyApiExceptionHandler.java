package com.footballmanagergamesimulator.economy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@RestControllerAdvice(assignableTypes = {PersonalEconomyController.class, MarketController.class,
        ClubController.class, ClubCashTransferController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EconomyApiExceptionHandler {

    @ExceptionHandler(EconomyConflictException.class)
    public ResponseEntity<EconomyDtos.ApiError> conflict(EconomyConflictException exception) {
        HttpStatus status;
        if (exception.getCode().endsWith("NOT_FOUND")) status = HttpStatus.NOT_FOUND;
        else if (exception.getCode().endsWith("FORBIDDEN")
                || exception.getCode().endsWith("_REQUIRED")) status = HttpStatus.FORBIDDEN;
        else status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(new EconomyDtos.ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<EconomyDtos.ApiError> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new EconomyDtos.ApiError("INVALID_REQUEST", exception.getMessage()));
    }
}
