package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Single source of truth for a player's <b>match value</b> and a team's matchday rating.
 *
 * <p>A starter's match value is his <em>special rating</em> at the position he occupies
 * (his attributes read through the match engine's weights, {@code compartment-scoring-weights-v1.yml}),
 * floored at {@code ratingFloor} (no maximum), then
 * {@code × familiarity(natural, used) × moraleFactor(morale) × fitnessFactor(fitness)}.
 * It never uses the visible rating's weights ({@code RatingWeightsConfig}).
 */
@Service
public class PlayerValueService {

    private final MatchEngineConfig engineConfig;
    private PositionAttributeImportance engineWeights;

    @Autowired
    public PlayerValueService(MatchEngineConfig engineConfig) {
        this.engineConfig = engineConfig;
    }

    /** Spring supplies the engine's weight table; otherwise the statically installed one is used. */
    @Autowired(required = false)
    void setEngineWeights(PositionAttributeImportance engineWeights) {
        this.engineWeights = engineWeights;
    }

    /**
     * The special rating at {@code usedPosition} on the ~1..300 scale, BEFORE
     * familiarity/morale/fitness.
     */
    public double computePositionalValue(PlayerSkills skills, String usedPosition) {
        MatchEngineConfig.PlayerValue cfg = engineConfig.getPlayerValue();
        PositionAttributeImportance table = engineWeights != null
                ? engineWeights : PositionAttributeImportance.current();
        double special = PlayerSkillsService.computeSpecialRating(skills, usedPosition, table);
        return Math.max(cfg.getRatingFloor(), special);
    }

    /** Position-familiarity factor in (0,1]: 1.0 on the natural position, configured penalty otherwise. */
    public double familiarityFactor(String naturalPosition, String usedPosition) {
        return engineConfig.getPlayerValue().familiarity(naturalPosition, usedPosition);
    }

    /** Per-player morale factor: {@code 1.0 + (morale - neutral) × slope}. */
    public double moraleFactor(double morale) {
        MatchEngineConfig.PlayerValue cfg = engineConfig.getPlayerValue();
        return 1.0 + (morale - cfg.getMoraleNeutral()) * cfg.getMoraleSlope();
    }

    /** Per-player fitness factor: {@code max(fitnessFloor, fitness / 100)}. */
    public double fitnessFactor(double fitness) {
        return Math.max(engineConfig.getPlayerValue().getFitnessFloor(), fitness / 100.0);
    }

    /**
     * The single source of truth for a starter's match value:
     * positional value × familiarity × morale × fitness.
     */
    public double evaluatePlayer(PlayerSkills skills, String naturalPosition, String usedPosition,
                                 double morale, double fitness) {
        return computePositionalValue(skills, usedPosition)
                * familiarityFactor(naturalPosition, usedPosition)
                * moraleFactor(morale)
                * fitnessFactor(fitness);
    }

    /**
     * Fallback for a starter with no {@code PlayerSkills} row (youth placeholder or data gap):
     * treat an externally supplied base value (e.g. the generic {@code Human.rating}) as the
     * positional value, then apply familiarity × morale × fitness exactly as
     * {@link #evaluatePlayer(PlayerSkills, String, String, double, double)}.
     */
    public double evaluatePlayer(double baseValue, String naturalPosition, String usedPosition,
                                 double morale, double fitness) {
        return baseValue
                * familiarityFactor(naturalPosition, usedPosition)
                * moraleFactor(morale)
                * fitnessFactor(fitness);
    }

    /** Team match value = sum of {@link #evaluatePlayer} over the eleven starters. */
    public double evaluateTeam(List<StarterAssignment> starters) {
        double total = 0;
        for (StarterAssignment s : starters) {
            total += evaluatePlayer(s.skills(), s.naturalPosition(), s.usedPosition(), s.morale(), s.fitness());
        }
        return total;
    }

    /**
     * Pairs a starter's skills with the base position slot they were assigned in the tactic
     * plus their current morale/fitness, so {@link #evaluateTeam} can apply familiarity and
     * the per-player factors.
     */
    public record StarterAssignment(PlayerSkills skills, String naturalPosition,
                                    String usedPosition, double morale, double fitness) {}
}
