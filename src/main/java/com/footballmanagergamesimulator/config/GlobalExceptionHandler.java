package com.footballmanagergamesimulator.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntimeException(RuntimeException e) {
        String message = e.getMessage();
        if (message != null && (message.contains("X-User-Id") || message.contains("User not found") || message.contains("User has no team"))) {
            return ResponseEntity.status(401).body(Map.of("error", message));
        }
        // For other RuntimeExceptions, return 400
        return ResponseEntity.badRequest().body(Map.of("error", message != null ? message : "Unknown error"));
    }
}
