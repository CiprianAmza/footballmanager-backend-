package com.footballmanagergamesimulator.economy;

public class EconomyConflictException extends RuntimeException {
    private final String code;

    public EconomyConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
