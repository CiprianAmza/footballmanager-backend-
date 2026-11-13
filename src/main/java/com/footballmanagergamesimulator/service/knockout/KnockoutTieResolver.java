package com.footballmanagergamesimulator.service.knockout;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.springframework.stereotype.Service;

import java.util.Random;

/**
 * Decides a level knockout tie after Compartment V1 has produced the normal-time aggregate
 * and the extra-time goals: extra time first, then a penalty shootout.
 */
@Service
public class KnockoutTieResolver {

    private final MatchEngineConfig config;

    public KnockoutTieResolver(MatchEngineConfig config) {
        this.config = config;
    }

    /**
     * Resolve a level canonical tie after its extra-time goals were sampled by Compartment V1.
     */
    public TieDecision decideCanonical(double strengthA, double strengthB,
                                       int aggregateA, int aggregateB,
                                       int extraTimeA, int extraTimeB,
                                       Random tiebreakRng) {
        if (aggregateA != aggregateB) {
            return new TieDecision(aggregateA > aggregateB, false, 0, 0, false, 0, 0);
        }
        if (aggregateA + extraTimeA != aggregateB + extraTimeB) {
            return new TieDecision((aggregateA + extraTimeA) > (aggregateB + extraTimeB),
                    true, extraTimeA, extraTimeB, false, 0, 0);
        }
        double weakerWinChance = config.getKnockout().getPenaltyWeakerTeamWinChance();
        boolean aIsWeaker = strengthA < strengthB;
        double aWinChance = aIsWeaker ? weakerWinChance : 1.0 - weakerWinChance;
        boolean aWon = tiebreakRng.nextDouble() < aWinChance;
        int[] penalties = penaltyScore(aWon, tiebreakRng);
        return new TieDecision(aWon, true, extraTimeA, extraTimeB, true, penalties[0], penalties[1]);
    }

    /**
     * Produce a compact, realistic shootout score while keeping the already
     * selected winner authoritative. Penalty kicks are deliberately separate
     * from the football score: callers expose them as a secondary result.
     */
    private int[] penaltyScore(boolean teamAWon, Random random) {
        int loser = 2 + random.nextInt(4); // 3-2 through 6-5 covers normal + sudden death
        int winner = loser + 1;
        return teamAWon ? new int[]{winner, loser} : new int[]{loser, winner};
    }

    /** Tiebreak outcome for an already-played aggregate. */
    public record TieDecision(boolean teamAWon, boolean extraTime, int etA, int etB,
                              boolean penalties, int penaltyA, int penaltyB) {}
}
