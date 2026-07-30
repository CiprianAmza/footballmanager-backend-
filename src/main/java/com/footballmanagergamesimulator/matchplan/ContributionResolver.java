package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.util.PositionScoringWeights;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The single, config-driven decision point for who scores and who assists a goal.
 * Used by BOTH the live executor and the instant/batch executor so the two paths
 * can never disagree. Given the players on the pitch at the goal's minute, it
 * chooses a scorer (position × finishing × rating × fitness, honouring a
 * designated penalty/free-kick taker) and, for open play, an assister from the
 * remaining on-pitch players. Passing 20 is exceptional: eligible perfect
 * passers share a fixed 70% assist chance; the remaining share is distributed
 * exclusively by the other players' Passing attribute.
 *
 * <p>Determinism: the caller passes a per-slot {@link Random} derived from the
 * plan seed and the slot index, kept separate from any cosmetic-event RNG, so
 * live and instant resolve identically regardless of invented misses/cards.
 */
@Service
public class ContributionResolver {

    private static final int PERFECT_PASSING = 20;

    private final MatchEngineConfig engineConfig;

    public ContributionResolver(MatchEngineConfig engineConfig) {
        this.engineConfig = engineConfig;
    }

    /**
     * Resolve {@code slot} against the players currently on the pitch. Mutates the
     * slot with the chosen scorer/assister and marks it resolved. No-op if the slot
     * is already resolved (idempotent replay) or no eligible player exists.
     */
    public void resolve(GoalSlot slot, List<Contributor> onPitch, Random rng) {
        if (slot == null || slot.isResolved()) return;
        if (onPitch == null || onPitch.isEmpty()) return;

        // Canonical candidate order (by playerId) so the pick depends only on
        // {seed, slotIndex, on-pitch set} — never on how a caller assembled the
        // list. This is what lets live and instant resolve identically.
        List<Contributor> canonical = new ArrayList<>(onPitch);
        canonical.sort(java.util.Comparator.comparingLong(Contributor::playerId));

        Contributor scorer = slot.getForcedScorerId() == null
                ? pickScorer(slot.getGoalType(), canonical, rng)
                : findForcedScorer(slot.getForcedScorerId(), canonical);
        if (scorer == null) return;

        Long assistId = null;
        if (!"PENALTY".equals(slot.getGoalType())) {
            boolean shooterGoal = "SHOOTER".equals(slot.getGoalType());
            List<Contributor> candidates = assistCandidates(canonical, scorer, shooterGoal);
            boolean hasPerfectPasser = candidates.stream()
                    .anyMatch(contributor -> contributor.passing() == PERFECT_PASSING);
            // A perfect passer's 70% is per goal, not 70% of the ordinary
            // assistProbability. SHOOTER goals also remain guaranteed assists.
            if (hasPerfectPasser || shooterGoal || shouldHaveAssist(slot.getGoalType(), rng)) {
                Contributor assister = pickAssister(candidates, rng);
                if (assister != null) assistId = assister.playerId();
            }
        }
        slot.resolve(scorer.playerId(), assistId);
    }

    private Contributor findForcedScorer(long playerId, List<Contributor> onPitch) {
        for (Contributor contributor : onPitch) {
            if (contributor.playerId() == playerId) return contributor;
        }
        return null;
    }

    private Contributor pickScorer(String goalType, List<Contributor> onPitch, Random rng) {
        // Honour a designated taker for set-piece goals when he is on the pitch.
        Contributor taker = designatedTaker(goalType, onPitch);
        if (taker != null) return taker;

        // Canonical scorer weight (hybrid): position × Finishing × rating²/70.
        // The quadratic rating (from the tuned legacy formula) lets star players
        // dominate goal share and net realistic hat-tricks; Finishing folds in the
        // named attribute; the position component carries the corrected AM weights.
        return PositionScoringWeights.weightedPick(
                onPitch,
                c -> c.isGoalkeeper() ? 0.0
                        : PositionScoringWeights.scorerWeight(c.position(), c.finishing())
                        * (ratingSquared(c.rating()) / 70.0),
                rng);
    }

    private double ratingSquared(double rating) {
        double r = Math.max(rating, 1.0);
        return r * r;
    }

    private List<Contributor> assistCandidates(
            List<Contributor> onPitch, Contributor scorer, boolean includeGoalkeeper) {
        List<Contributor> candidates = new ArrayList<>();
        for (Contributor c : onPitch) {
            if (c.playerId() == scorer.playerId()) continue;
            if (!includeGoalkeeper && c.isGoalkeeper()) continue;
            candidates.add(c);
        }
        return candidates;
    }

    /**
     * Passing 20 is a discrete playmaking ability. When at least one eligible
     * perfect passer is not the scorer, that group shares the configured 70%.
     * On a miss of that roll, only the other players contest the remaining 30%,
     * weighted directly by Passing. If no perfect passer exists, the whole
     * selection is Passing-weighted.
     */
    private Contributor pickAssister(List<Contributor> candidates, Random rng) {
        if (candidates.isEmpty()) return null;

        List<Contributor> perfectPassers = candidates.stream()
                .filter(contributor -> contributor.passing() == PERFECT_PASSING)
                .toList();
        if (!perfectPassers.isEmpty()) {
            double probability = Math.max(0.0, Math.min(1.0,
                    engineConfig.getEvents().getPerfectPassingAssistProbability()));
            if (rng.nextDouble() < probability) {
                return passingWeightedPick(perfectPassers, rng);
            }
            List<Contributor> otherPlayers = candidates.stream()
                    .filter(contributor -> contributor.passing() != PERFECT_PASSING)
                    .toList();
            // A real XI always has another candidate. This fallback only covers
            // synthetic/minimal lineups and still guarantees a valid assister.
            if (otherPlayers.isEmpty()) return passingWeightedPick(perfectPassers, rng);
            return passingWeightedPick(otherPlayers, rng);
        }
        return passingWeightedPick(candidates, rng);
    }

    private Contributor passingWeightedPick(List<Contributor> candidates, Random rng) {
        return PositionScoringWeights.weightedPick(
                candidates, contributor -> Math.max(contributor.passing(), 0), rng);
    }

    private Contributor designatedTaker(String goalType, List<Contributor> onPitch) {
        if ("PENALTY".equals(goalType)) {
            for (Contributor c : onPitch) if (c.designatedPenaltyTaker()) return c;
        } else if ("FREE_KICK".equals(goalType)) {
            for (Contributor c : onPitch) if (c.designatedFreeKickTaker()) return c;
        }
        return null;
    }

    private boolean shouldHaveAssist(String goalType, Random rng) {
        if ("PENALTY".equals(goalType)) return false;
        return rng.nextDouble() < engineConfig.getEvents().getAssistProbability();
    }
}
