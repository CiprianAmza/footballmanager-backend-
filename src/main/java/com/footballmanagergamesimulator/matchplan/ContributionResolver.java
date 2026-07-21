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
 * designated penalty/free-kick taker) and, for open play, an assister
 * (position × passing/vision × rating) from the remaining on-pitch players.
 *
 * <p>Determinism: the caller passes a per-slot {@link Random} derived from the
 * plan seed and the slot index, kept separate from any cosmetic-event RNG, so
 * live and instant resolve identically regardless of invented misses/cards.
 */
@Service
public class ContributionResolver {

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

        Contributor scorer = pickScorer(slot.getGoalType(), canonical, rng);
        if (scorer == null) return;

        Long assistId = null;
        if (shouldHaveAssist(slot.getGoalType(), rng)) {
            Contributor assister = pickAssister(canonical, scorer, rng);
            if (assister != null) assistId = assister.playerId();
        }
        slot.resolve(scorer.playerId(), assistId);
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

    private Contributor pickAssister(List<Contributor> onPitch, Contributor scorer, Random rng) {
        List<Contributor> candidates = new ArrayList<>();
        for (Contributor c : onPitch) {
            if (c.playerId() == scorer.playerId()) continue;
            if (c.isGoalkeeper()) continue;
            candidates.add(c);
        }
        if (candidates.isEmpty()) return null;
        return PositionScoringWeights.weightedPick(
                candidates,
                c -> PositionScoringWeights.assistWeight(c.position(), c.passing(), c.vision())
                        * Math.max(c.rating(), 1.0),
                rng);
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
