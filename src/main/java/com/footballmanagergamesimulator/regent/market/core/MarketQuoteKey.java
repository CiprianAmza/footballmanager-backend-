package com.footballmanagergamesimulator.regent.market.core;

import java.util.Objects;

/** Complete identity of a deterministic daily market calculation. */
public record MarketQuoteKey(long saveSeed, String instrumentId, long day, String saveVersion) {
    public MarketQuoteKey {
        instrumentId = requireText(instrumentId, "instrumentId");
        saveVersion = requireText(saveVersion, "saveVersion");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
