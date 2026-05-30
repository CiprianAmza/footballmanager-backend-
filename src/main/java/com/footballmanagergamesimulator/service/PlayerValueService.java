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
 * <p>A starter's match value is
 * <pre>{@code clamp(weightedAvgAttrs × scaleMultiplier, floor, ceil)
 *        × familiarity(natural, used) × moraleFactor(morale) × fitnessFactor(fitness)}</pre>
 * and a team's value is the sum over its eleven starters. That team value is what feeds
 * {@code MatchSimulationService.calculateScores} (after the team-level team-talk and
 * home-advantage multipliers in {@code effectivePower}).
 *
 * <p>This is deliberately distinct from {@code PlayerSkillsService.computeOverallRating}
 * (the generic skill stored on {@code Human.rating}): that generic value is used only for
 * squad-selection sorting, transfer valuations and UI. The match value uses per-position
 * attribute weights ({@code match.engine.player-value.weights}) so designers can express
 * what matters per position, and it is computed only at matchday.
 */
@Service
public class PlayerValueService {

    private final MatchEngineConfig engineConfig;

    @Autowired
    public PlayerValueService(MatchEngineConfig engineConfig) {
        this.engineConfig = engineConfig;
    }

    /**
     * Position-weighted attribute value on the same ~1..300 scale as {@code Human.rating},
     * BEFORE familiarity/morale/fitness. {@code usedPosition} is the base position the
     * player occupies in the tactic (GK/DL/DR/DC/ML/MR/MC/ST).
     */
    public double computePositionalValue(PlayerSkills skills, String usedPosition) {
        MatchEngineConfig.PlayerValue cfg = engineConfig.getPlayerValue();
        double weightedSum = 0;
        double weightTotal = 0;
        for (Map.Entry<String, Function<PlayerSkills, Integer>> e : PlayerSkillsService.GETTER_MAP.entrySet()) {
            double w = cfg.weight(usedPosition, e.getKey());
            if (w == 0) continue;
            weightedSum += e.getValue().apply(skills) * w;
            weightTotal += w;
        }
        double avg = weightTotal > 0 ? weightedSum / weightTotal : 0;
        double scaled = avg * cfg.getScaleMultiplier();
        return Math.max(cfg.getRatingFloor(), Math.min(cfg.getRatingCeil(), scaled));
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
