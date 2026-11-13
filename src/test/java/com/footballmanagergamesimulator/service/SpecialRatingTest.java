package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.RatingWeightsConfig;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SpecialRatingTest {

    private PositionAttributeImportance previousTable;
    private RatingWeightsConfig previousRatingWeights;
    private PositionAttributeImportance engineWeights;

    @BeforeEach
    void installEngineWeights() {
        previousTable = PositionAttributeImportance.current();
        previousRatingWeights = RatingWeightsConfig.current();
        engineWeights = PositionAttributeImportance.install(loadEngineConfig());
        RatingWeightsConfig.install(null);
    }

    @AfterEach
    void restore() {
        PositionAttributeImportance.restore(previousTable);
        RatingWeightsConfig.install(previousRatingWeights);
    }

    private static CompartmentEngineConfig loadEngineConfig() {
        try {
            MutablePropertySources properties = new MutablePropertySources();
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("weights", new ClassPathResource("compartment-scoring-weights-v1.yml"))) {
                properties.addLast(source);
            }
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"))) {
                properties.addLast(source);
            }
            return new Binder(ConfigurationPropertySources.from(properties))
                    .bind("match.engine.compartment", Bindable.of(CompartmentEngineConfig.class))
                    .orElseThrow(IllegalStateException::new);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private PlayerSkills generated(String position, double rating, long seed) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition(position);
        new CompetitionService().generateSkills(skills, rating, new Random(seed));
        return skills;
    }

    @Test
    @DisplayName("""
            GIVEN a striker with the built-in rating weights and the engine weights
            WHEN both of his ratings are computed
            THEN they come from different weight tables and differ
            """)
    void theTwoRatingsUseDifferentWeights() {
        PlayerSkills striker = generated("ST", 180, 7);

        assertNotEquals(PlayerSkillsService.computeSpecialRating(striker),
                PlayerSkillsService.computeOverallRating(striker), 1e-6);
    }

    @Test
    @DisplayName("""
            GIVEN the striker's rating weights are reconfigured to count only finishing
            WHEN both ratings are computed again
            THEN the visible rating changes and the special rating does not
            """)
    void ratingWeightsDoNotTouchTheSpecialRating() {
        PlayerSkills striker = generated("ST", 180, 8);
        striker.setFinishing(18);
        double specialBefore = PlayerSkillsService.computeSpecialRating(striker);
        double ratingBefore = PlayerSkillsService.computeOverallRating(striker);

        RatingWeightsConfig finishingOnly = new RatingWeightsConfig();
        finishingOnly.setPositions(Map.of("ST", Map.of("Finishing", 1.0)));
        RatingWeightsConfig.install(finishingOnly);

        assertEquals(specialBefore, PlayerSkillsService.computeSpecialRating(striker), 1e-9);
        assertEquals((18 - 1) / 18.0 * 300, PlayerSkillsService.computeOverallRating(striker), 1e-9);
        assertNotEquals(ratingBefore, PlayerSkillsService.computeOverallRating(striker), 1e-6);
    }

    @Test
    @DisplayName("""
            GIVEN no engine weight table at all
            WHEN the visible rating is computed
            THEN it still works, because it never depends on the engine's weights
            """)
    void theRatingDoesNotNeedTheEngineWeights() {
        PlayerSkills striker = generated("ST", 180, 9);
        double withEngineTable = PlayerSkillsService.computeOverallRating(striker);

        PositionAttributeImportance.restore(null);

        assertEquals(withEngineTable, PlayerSkillsService.computeOverallRating(striker), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN a fine position that is not configured (AMC)
            WHEN its rating weights are looked up
            THEN it uses its base position's table (MC)
            """)
    void finePositionsUseTheirBaseTable() {
        RatingWeightsConfig weights = RatingWeightsConfig.current();

        assertEquals(weights.weightsFor("MC"), weights.weightsFor("AMC"));
    }

    @Test
    @DisplayName("""
            GIVEN a striker generated for a rating of 150
            WHEN his attributes are calibrated to a rating of 150
            THEN his visible rating lands near 150
            """)
    void calibrationTargetsTheVisibleRating() {
        PlayerSkills striker = generated("ST", 150, 10);

        PlayerSkillsService.calibrateOverallRating(striker, 150);

        assertEquals(150, PlayerSkillsService.computeOverallRating(striker), 3.0);
    }
}
