package com.footballmanagergamesimulator.service;

/**
 * Pure-math estimator for a player's transfer value. Lives in the service
 * layer so any caller (controllers, services, scheduled jobs) can use it
 * without dragging in a controller dependency. The legacy
 * {@code CompetitionController.calculateTransferValue} now delegates here.
 */
public final class TransferValueCalculator {

    private TransferValueCalculator() { /* static-only */ }

    /**
     * Exponential scaling by rating with an age multiplier. Top players
     * (~rating 200) land near 160M, average (~100) near 20M, weak (~50) near
     * 2.5M; ages above 27 take an accelerating discount.
     */
    public static long calculate(long age, String position, double rating) {
        double baseValue = Math.pow(rating, 3) * 20;

        double ageMultiplier;
        if (age <= 21) ageMultiplier = 1.3;     // youth premium
        else if (age <= 23) ageMultiplier = 1.1;
        else if (age <= 25) ageMultiplier = 1.0;
        else if (age <= 27) ageMultiplier = 0.95;
        else if (age <= 29) ageMultiplier = 0.75;
        else if (age <= 31) ageMultiplier = 0.45;
        else if (age <= 33) ageMultiplier = 0.2;
        else ageMultiplier = 0.08;

        return Math.max(50_000L, (long) (baseValue * ageMultiplier));
    }
}
