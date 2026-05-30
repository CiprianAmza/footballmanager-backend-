package com.footballmanagergamesimulator.service.knockout;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.TacticalScoreService;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

/**
 * Resolves a knockout tie between two teams, single-leg or two-leg, producing a
 * decisive winner. Shared by the outcome-simulation tests and (eventually) the
 * live match engine, so the rules live in one place.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Single-leg</b>: one match. Level after 90' → extra time
 *       ({@code knockout.extraTimeExpectedGoals}) → still level → penalties.</li>
 *   <li><b>Two-leg</b>: leg 1 (A home) + leg 2 (B home), each can end in a draw.
 *       Decided on aggregate. Level aggregate → extra time in the second leg →
 *       still level → penalties. No away-goals rule.</li>
 * </ul>
 *
 * <h2>Randomness</h2>
 * Match/extra-time goals come from {@link MatchSimulationService#calculateScores}
 * (which uses that service's RNG — seed it with {@code setRandomForTesting} for
 * determinism). The penalty shootout coin-flip uses the {@code tiebreakRng}
 * passed in, so callers can keep score RNG and shootout RNG on separate seeded
 * streams.
 */
@Service
public class KnockoutTieResolver {

    private final MatchSimulationService matchSim;
    private final MatchEngineConfig config;
    private final TacticalScoreService tacticalScoreService;

    public KnockoutTieResolver(MatchSimulationService matchSim, MatchEngineConfig config,
                               TacticalScoreService tacticalScoreService) {
        this.matchSim = matchSim;
        this.config = config;
        this.tacticalScoreService = tacticalScoreService;
    }

    /**
     * Resolve a tie. "A" is the first team (hosts leg 1 in a two-leg tie).
     *
     * @param powerA     team A power rating
     * @param powerB     team B power rating
     * @param format     single-leg or two-leg
     * @param tiebreakRng RNG used only for the penalty shootout coin-flip
     * @return a decisive {@link TieResult} ({@link TieResult#teamAWon()} is never an "undecided" state)
     */
    public TieResult resolve(double powerA, double powerB, LegFormat format, Random tiebreakRng) {
        int leg1A, leg1B, leg2A, leg2B, aggA, aggB;

        if (format == LegFormat.TWO_LEG) {
            List<Integer> leg1 = matchSim.calculateScores(powerA, powerB);
            leg1A = leg1.get(0);
            leg1B = leg1.get(1);
            // Leg 2 is at B's ground, so calculateScores is called B-first; map back to A/B.
            List<Integer> leg2 = matchSim.calculateScores(powerB, powerA);
            leg2B = leg2.get(0);
            leg2A = leg2.get(1);
            aggA = leg1A + leg2A;
            aggB = leg1B + leg2B;
        } else {
            List<Integer> match = matchSim.calculateScores(powerA, powerB);
            leg1A = match.get(0);
            leg1B = match.get(1);
            leg2A = -1;
            leg2B = -1;
            aggA = leg1A;
            aggB = leg1B;
        }

        TieDecision d = decide(powerA, powerB, aggA, aggB, tiebreakRng);
        return new TieResult(format, leg1A, leg1B, leg2A, leg2B, aggA, aggB,
                d.extraTime(), d.etA(), d.etB(), d.penalties(), d.teamAWon());
    }

    /**
     * Decide a tie from already-played scores. {@code aggregateA/B} is the
     * normal-time aggregate (or a single match's score). If level, plays a
     * 30-minute extra-time mini-match; if still level, a penalty shootout.
     * Used by the live engine, where the legs have already been simulated
     * (with their own events/stats), so only the tiebreak remains.
     *
     * @param tiebreakRng RNG for the extra-time goals and the shootout coin-flip
     */
    public TieDecision decide(double powerA, double powerB, int aggregateA, int aggregateB, Random tiebreakRng) {
        if (aggregateA != aggregateB) {
            return new TieDecision(aggregateA > aggregateB, false, 0, 0, false);
        }

        // Level → 30-minute extra-time mini-match (far fewer goals than a full 90).
        double etGoals = config.getKnockout().getExtraTimeExpectedGoals();
        List<Integer> et = matchSim.calculateScores(powerA, powerB, etGoals);
        int etA = et.get(0);
        int etB = et.get(1);
        if (aggregateA + etA != aggregateB + etB) {
            return new TieDecision((aggregateA + etA) > (aggregateB + etB), true, etA, etB, false);
        }

        // Still level → penalty shootout. Near coin-flip, optional weaker-team edge.
        double weakerWinChance = config.getKnockout().getPenaltyWeakerTeamWinChance();
        boolean aIsWeaker = powerA < powerB;
        double aWinChance = aIsWeaker ? weakerWinChance : 1.0 - weakerWinChance;
        boolean aWon = tiebreakRng.nextDouble() < aWinChance;
        return new TieDecision(aWon, true, etA, etB, true);
    }

    /**
     * Two-axis variant of {@link #decide(double, double, int, int, Random)}: the extra-time
     * mini-match is played on the attack-vs-defense {@link TacticalScoreService} model (the
     * production engine when {@code tactical-model.enabled}) instead of the scalar engine. The
     * penalty weaker-team edge compares total profile strength (attack + defense).
     *
     * @param pA team A coached profile (team-talk-scaled), tA its tactic vector
     * @param pB team B coached profile, tB its tactic vector
     * @param tiebreakRng RNG for the extra-time goals and the shootout coin-flip
     */
    public TieDecision decide(TeamProfile pA, TacticVector tA, TeamProfile pB, TacticVector tB,
                              int aggregateA, int aggregateB, Random tiebreakRng) {
        if (aggregateA != aggregateB) {
            return new TieDecision(aggregateA > aggregateB, false, 0, 0, false);
        }

        List<Integer> et = tacticalScoreService.scoreExtraTime(pA, tA, pB, tB, tiebreakRng);
        int etA = et.get(0);
        int etB = et.get(1);
        if (aggregateA + etA != aggregateB + etB) {
            return new TieDecision((aggregateA + etA) > (aggregateB + etB), true, etA, etB, false);
        }

        double weakerWinChance = config.getKnockout().getPenaltyWeakerTeamWinChance();
        boolean aIsWeaker = (pA.attack() + pA.defense()) < (pB.attack() + pB.defense());
        double aWinChance = aIsWeaker ? weakerWinChance : 1.0 - weakerWinChance;
        boolean aWon = tiebreakRng.nextDouble() < aWinChance;
        return new TieDecision(aWon, true, etA, etB, true);
    }

    /** Tiebreak outcome for an already-played aggregate. */
    public record TieDecision(boolean teamAWon, boolean extraTime, int etA, int etB, boolean penalties) {}
}
