package com.footballmanagergamesimulator.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class CareerControlConflictException extends RuntimeException {
    public CareerControlConflictException(String message) {
        super(message);
    }
}
