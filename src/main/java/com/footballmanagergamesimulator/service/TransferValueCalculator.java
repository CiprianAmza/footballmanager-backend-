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
     * 2.5M. The age curve mirrors the ability curve in {@code TrainingService}:
     * a youth premium, an extended prime plateau across 24-33 (with a gentle
     * taper that still rewards youth at resale), then a sharp cliff from 34.
     */
    public static long calculate(long age, String position, double rating) {
        double baseValue = Math.pow(rating, 3) * 20;

        double ageMultiplier;
        if (age <= 21) ageMultiplier = 1.30;     // youth premium (potential)
        else if (age <= 23) ageMultiplier = 1.15;
        else if (age <= 27) ageMultiplier = 1.00; // prime plateau
        else if (age <= 31) ageMultiplier = 0.90;
        else if (age <= 33) ageMultiplier = 0.80;
        else if (age <= 35) ageMultiplier = 0.45; // cliff begins at 34
        else ageMultiplier = 0.20;

        return Math.max(50_000L, (long) (baseValue * ageMultiplier));
    }
}
