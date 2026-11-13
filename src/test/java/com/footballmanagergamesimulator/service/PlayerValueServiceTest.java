package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-logic tests for {@link PlayerValueService}: the special rating at the occupied position,
 * times familiarity, morale and fitness. Uses the real engine weights, without Spring.
 */
class PlayerValueServiceTest {

    private PositionAttributeImportance previousTable;

    @BeforeEach
    void installEngineWeights() throws IOException {
        previousTable = PositionAttributeImportance.current();
        MutablePropertySources properties = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("weights", new ClassPathResource("compartment-scoring-weights-v1.yml"))) {
            properties.addLast(source);
        }
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            properties.addLast(source);
        }
        PositionAttributeImportance.install(new Binder(ConfigurationPropertySources.from(properties))
                .bind("match.engine.compartment", Bindable.of(CompartmentEngineConfig.class))
                .orElseThrow(IllegalStateException::new));
    }

    @AfterEach
    void restoreLiveTable() {
        PositionAttributeImportance.restore(previousTable);
    }

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

        PlayerSkills s = uniformSkills("ST", 10);
        double expected = svc.computePositionalValue(s, "DC")   // special rating at the occupied slot
                * 0.2                                           // familiarity ST→DC
                * svc.moraleFactor(80)
                * svc.fitnessFactor(90);

        assertThat(svc.evaluatePlayer(s, "ST", "DC", 80, 90)).isCloseTo(expected, within(1e-6));
    }

    @Test
    void evaluatePlayer_hasNoCeiling_everyPointAbove19Counts() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        PlayerValueService svc = service(cfg);

        double at19 = svc.evaluatePlayer(uniformSkills("ST", 19), "ST", "ST", 70, 100);
        double at20 = svc.evaluatePlayer(uniformSkills("ST", 20), "ST", "ST", 70, 100);
        double at21 = svc.evaluatePlayer(uniformSkills("ST", 21), "ST", "ST", 70, 100);
        assertThat(at20).isGreaterThan(at19).isGreaterThan(300.0);
        assertThat(at21).isGreaterThan(at20);
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
