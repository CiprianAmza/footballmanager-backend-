package com.footballmanagergamesimulator.service;

import java.util.Random;

/**
 * Round-based fee talks with the selling club (decided 2026-11-12). Pure rules.
 * <ul>
 *   <li>The club asks what it wants ({@link TransferPricing#clubWants}: the real price −35%…+40%); I see the ask.</li>
 *   <li>It will not take less than its minimum: the ask minus a random 0–30% (I do not see it).</li>
 *   <li>Each round my offer reaches its ask → fee agreed (or the release clause). Far too low (under 80% of its
 *       minimum) → it leaves the table. Otherwise it comes down a random 25–75% of the way towards my offer,
 *       never under its minimum: with endless rounds I could reach the minimum, never less.</li>
 *   <li>Every club has its own patience, {@link #roundsOf} 3–10 rounds. At the last one it states its ask as a
 *       take-it-or-leave-it offer. If we do not agree, the talks are blocked for 3–10 days.</li>
 * </ul>
 */
public final class ClubNegotiation {

    /** The most the club will come down from its ask: a random 0–30%, fixed when the talks open. */
    public static final double MAX_DISCOUNT = 0.30;
    public static final int MIN_ROUNDS = 3;
    public static final int MAX_ROUNDS = 10;
    public static final int MIN_COOLDOWN_DAYS = 3;
    public static final int MAX_COOLDOWN_DAYS = 10;
    /** An offer under this share of the club's minimum is too weak: the club ends the talks (tunable). */
    public static final double WALK_AWAY_SHARE = 0.80;
    /** Each round the club gives up this random share of the gap between its ask and my offer. */
    public static final double MIN_CONCESSION = 0.25;
    public static final double MAX_CONCESSION = 0.75;

    public enum Outcome { FEE_AGREED, TURNED_DOWN, FINAL_OFFER, CLUB_WALKED_AWAY }

    /** The club's answer: the outcome and its ask from now on. */
    public record Answer(Outcome outcome, long ask) {
    }

    private ClubNegotiation() {
    }

    /** A club's patience in rounds, fixed per club (3–10). */
    public static int roundsOf(long clubId) {
        return MIN_ROUNDS + (int) Math.floorMod(clubId * 7919L, (long) (MAX_ROUNDS - MIN_ROUNDS + 1));
    }

    /** The least the club takes: its ask minus a random 0–30%. */
    public static long minimum(long ask, Random rng) {
        return Math.round(ask * (1 - MAX_DISCOUNT * rng.nextDouble()));
    }

    /** Days the talks stay blocked after failing: 3–10. */
    public static int cooldownDays(Random rng) {
        return MIN_COOLDOWN_DAYS + rng.nextInt(MAX_COOLDOWN_DAYS - MIN_COOLDOWN_DAYS + 1);
    }

    /**
     * The club's answer to my offer in {@code round} (1-based) of {@code rounds}: agreed, too weak (it leaves),
     * or it comes down a random 25–75% of the way towards my offer, never under {@code minimum}.
     */
    public static Answer answer(long amount, long ask, long minimum, long releaseClause, int round, int rounds,
                                Random rng) {
        if (amount >= ask || (releaseClause > 0 && amount >= releaseClause)) return new Answer(Outcome.FEE_AGREED, ask);
        if (amount < Math.round(minimum * WALK_AWAY_SHARE)) return new Answer(Outcome.CLUB_WALKED_AWAY, ask);
        long target = Math.max(amount, minimum);
        double share = MIN_CONCESSION + (MAX_CONCESSION - MIN_CONCESSION) * rng.nextDouble();
        long next = Math.max(minimum, ask - (long) Math.ceil((ask - target) * share));
        if (amount >= next) return new Answer(Outcome.FEE_AGREED, amount);
        return new Answer(round + 1 >= rounds ? Outcome.FINAL_OFFER : Outcome.TURNED_DOWN, next);
    }
}
