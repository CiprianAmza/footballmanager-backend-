package com.footballmanagergamesimulator.service;

/**
 * Single source of truth for the player wage curve. Squad generation and
 * contract negotiation previously derived wages from two independent formulas
 * (linear {@code rating * 50} vs exponential {@code pow(rating/10, 2.5) * 500}),
 * diverging ~100x. Both paths now share {@link #baseWage(double)} so a freshly
 * generated player's wage equals his neutral negotiation demand.
 *
 * <p>Pure function — exposed statically so callers don't need to inject it.
 */
public final class WageService {

    private WageService() {}

    /**
     * Neutral base wage from rating (1-300 scale) using the exponential demand
     * curve. This is the wage a player demands with no age/morale/playing-time/
     * squad-rank modifiers applied — also the wage a player is generated with.
     */
    public static long baseWage(double rating) {
        return (long) (Math.pow(rating / 10.0, 2.5) * 500);
    }
}
