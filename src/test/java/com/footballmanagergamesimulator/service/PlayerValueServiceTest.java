package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-logic tests for {@link PlayerValueService} — the config-driven matchday value engine.
 * Builds a plain {@link MatchEngineConfig} (the same object production binds from YAML) so
 * production and tests share one source of truth, without standing up Spring.
 */
class PlayerValueServiceTest {

    /** A PlayerSkills with every one of the 36 attributes set to {@code v}. */
    private PlayerSkills uniformSkills(String position, int v) {
        PlayerSkills s = new PlayerSkills();
        s.setPosition(position);
        PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(s, v));
        return s;
    }

    private PlayerValueService service(MatchEngineConfig cfg) {
        return new PlayerValueService(cfg);
    }

    @Test
    void uniformSkills_giveTheSameValueRegardlessOfPositionWeights() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        PlayerValueService svc = service(cfg);
        PlayerSkills s = uniformSkills("ST", 10);

        // Any weighted average of all-10 attributes is 10 → ×scaleMultiplier(15) = 150, so the
        // value is position-independent for a uniform player whatever the per-position weights are.
        assertThat(svc.computePositionalValue(s, "ST")).isCloseTo(150.0, within(1e-9));
        assertThat(svc.computePositionalValue(s, "GK")).isCloseTo(150.0, within(1e-9));
        assertThat(svc.computePositionalValue(s, "DC")).isCloseTo(150.0, within(1e-9));
    }

    @Test
    void higherWeightOnAnAttribute_shiftsValueTowardThatAttribute() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        PlayerValueService svc = service(cfg);

        PlayerSkills s = uniformSkills("ST", 10);
        s.setFinishing(20); // one standout attribute

        // Override only Finishing's weight (other attrs keep their ST default profile); raising it
        // must pull the weighted average toward the standout 20.
        cfg.getPlayerValue().setWeights(Map.of("ST", Map.of("Finishing", 1.0)));
        double lowFinishingWeight = svc.computePositionalValue(s, "ST");

        cfg.getPlayerValue().setWeights(Map.of("ST", Map.of("Finishing", 5.0)));
        double highFinishingWeight = svc.computePositionalValue(s, "ST");

        assertThat(highFinishingWeight).isGreaterThan(lowFinishingWeight);
    }

    @Test
    void zeroWeight_excludesAttributeEntirely() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        cfg.getPlayerValue().setWeights(Map.of("ST", Map.of("Finishing", 0.0)));
        PlayerValueService svc = service(cfg);

        PlayerSkills s = uniformSkills("ST", 10);
        s.setFinishing(20); // would raise the mean if counted

        // Finishing excluded ⇒ remaining 35 attrs all 10 ⇒ mean 10 × 15 = 150
        assertThat(svc.computePositionalValue(s, "ST")).isCloseTo(150.0, within(1e-9));
    }

    @Test
    void familiarity_naturalIsOne_overrideWins_thenDefaultMatrix_thenDefaultPenalty() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        cfg.getPlayerValue().setDefaultFamiliarityPenalty(0.5);
        cfg.getPlayerValue().setFamiliarityPenalty(Map.of("MR", Map.of("ML", 0.95)));
        PlayerValueService svc = service(cfg);

        assertThat(svc.familiarityFactor("ST", "ST")).isEqualTo(1.0);              // natural
        assertThat(svc.familiarityFactor("MR", "ML")).isEqualTo(0.95);             // override wins
        assertThat(svc.familiarityFactor("ST", "DC")).isEqualTo(0.2);              // shipped default matrix
        assertThat(svc.familiarityFactor("XX", "YY")).isEqualTo(0.5);              // unknown ⇒ default penalty
    }

    @Test
    void moraleAndFitnessFactors_matchTheConfiguredCurves() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        PlayerValueService svc = service(cfg);

        assertThat(svc.moraleFactor(70)).isCloseTo(1.0, within(1e-9));   // neutral
        assertThat(svc.moraleFactor(100)).isGreaterThan(svc.moraleFactor(50));

        assertThat(svc.fitnessFactor(100)).isCloseTo(1.0, within(1e-9));
        assertThat(svc.fitnessFactor(0)).isCloseTo(0.7, within(1e-9));   // floor
    }

    @Test
    void evaluatePlayer_isProductOfTheFourFactors() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        cfg.getPlayerValue().setFamiliarityPenalty(Map.of("ST", Map.of("DC", 0.2)));
        PlayerValueService svc = service(cfg);

        PlayerSkills s = uniformSkills("ST", 10); // positional value 150
        double expected = 150.0
                * 0.2                               // familiarity ST→DC
                * svc.moraleFactor(80)
                * svc.fitnessFactor(90);

        assertThat(svc.evaluatePlayer(s, "ST", "DC", 80, 90)).isCloseTo(expected, within(1e-6));
    }

    @Test
    void evaluatePlayer_clampsToCeil() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        cfg.getPlayerValue().setScaleMultiplier(100.0); // 20 × 100 = 2000, far above ceil
        PlayerValueService svc = service(cfg);

        PlayerSkills s = uniformSkills("ST", 20);
        // positional value clamps to ceil 300; on-position, neutral morale/fitness ⇒ ~300
        double value = svc.evaluatePlayer(s, "ST", "ST", 70, 100);
        assertThat(value).isCloseTo(300.0, within(1e-6));
    }

    @Test
    void evaluateTeam_sumsStarterValues() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        PlayerValueService svc = service(cfg);

        PlayerSkills a = uniformSkills("ST", 10);
        PlayerSkills b = uniformSkills("DC", 12);
        double individual = svc.evaluatePlayer(a, "ST", "ST", 70, 100)
                + svc.evaluatePlayer(b, "DC", "DC", 70, 100);

        double team = svc.evaluateTeam(List.of(
                new PlayerValueService.StarterAssignment(a, "ST", "ST", 70, 100),
                new PlayerValueService.StarterAssignment(b, "DC", "DC", 70, 100)));

        assertThat(team).isCloseTo(individual, within(1e-9));
    }
}
