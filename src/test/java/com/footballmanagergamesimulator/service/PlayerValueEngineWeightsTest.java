package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerValueEngineWeightsTest {

    private PositionAttributeImportance previousTable;
    private PositionAttributeImportance engineWeights;
    private final MatchEngineConfig matchConfig = new MatchEngineConfig();

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
        CompartmentEngineConfig config = new Binder(ConfigurationPropertySources.from(properties))
                .bind("match.engine.compartment", Bindable.of(CompartmentEngineConfig.class))
                .orElseThrow(IllegalStateException::new);
        engineWeights = PositionAttributeImportance.install(config);
    }

    @AfterEach
    void restoreLiveTable() {
        PositionAttributeImportance.restore(previousTable);
    }

    private PlayerSkills striker(double rating) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition("ST");
        new CompetitionService().generateSkills(skills, rating, new Random(11));
        return skills;
    }

    @Test
    @DisplayName("""
            GIVEN the engine's weight table is available
            WHEN a striker's match value is computed at his own position
            THEN it equals his special rating (the engine's weights)
            """)
    void matchValueIsTheSpecialRating() {
        PlayerValueService service = new PlayerValueService(matchConfig);
        service.setEngineWeights(engineWeights);
        PlayerSkills skills = striker(200);

        assertEquals(PlayerSkillsService.computeSpecialRating(skills, "ST", engineWeights),
                service.computePositionalValue(skills, "ST"), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN a striker played as a central defender
            WHEN his match value is computed at the position he occupies
            THEN it is read through the defender's engine weights and is lower than at striker
            """)
    void matchValueUsesThePositionOccupied() {
        PlayerValueService service = new PlayerValueService(matchConfig);
        service.setEngineWeights(engineWeights);
        PlayerSkills skills = striker(200);

        double asDefender = service.computePositionalValue(skills, "DC");

        assertEquals(PlayerSkillsService.computeSpecialRating(skills, "DC", engineWeights), asDefender, 1e-9);
        assertTrue(asDefender < service.computePositionalValue(skills, "ST"),
                "as DC " + asDefender + " vs as ST " + service.computePositionalValue(skills, "ST"));
    }

    @Test
    @DisplayName("""
            GIVEN a striker
            WHEN his match value is computed
            THEN it is his special rating, not his visible rating
            """)
    void matchValueIgnoresTheVisibleRatingWeights() {
        PlayerValueService service = new PlayerValueService(matchConfig);
        service.setEngineWeights(engineWeights);
        PlayerSkills skills = striker(200);

        double value = service.computePositionalValue(skills, "ST");

        assertEquals(PlayerSkillsService.computeSpecialRating(skills, "ST", engineWeights), value, 1e-9);
        assertTrue(Math.abs(value - PlayerSkillsService.computeOverallRating(skills)) > 1e-6,
                "match value " + value + " must not be the visible rating");
    }
}
