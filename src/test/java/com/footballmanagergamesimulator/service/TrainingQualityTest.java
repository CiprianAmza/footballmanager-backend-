package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingQualityTest {

    private final TrainingQuality product = new TrainingQuality();

    private TrainingQuality average() {
        TrainingQuality quality = new TrainingQuality();
        quality.setCombination(TrainingQuality.Combination.AVERAGE);
        return quality;
    }

    @Test
    @DisplayName("""
            GIVEN the seven training types
            WHEN their attributes are listed
            THEN every player attribute belongs to exactly one type, and each type has two coach attributes
            """)
    void everyAttributeHasExactlyOneType() {
        Set<String> seen = new HashSet<>();
        for (TrainingType type : TrainingType.values()) {
            for (String attribute : type.attributes()) {
                assertTrue(seen.add(attribute), attribute + " is in two types");
                assertEquals(type, TrainingType.of(attribute));
            }
            assertEquals(2, type.coachAttributes().size());
        }
        assertEquals(PlayerSkillsService.GETTER_MAP.keySet(), seen);
    }

    @Test
    @DisplayName("""
            GIVEN the product combination
            WHEN q is computed for a few clubs
            THEN all at 20 gives 1, all at 10 gives 1/6, facilities 20 with the rest at 10 gives 1/3
            """)
    void productCombination() {
        assertEquals(1.0, product.q(20, 20, 20), 1e-9);
        assertEquals(1.0 / 6, product.q(10, 10, 10), 1e-9);
        assertEquals(1.0 / 3, product.q(20, 10, 10), 1e-9);
        assertEquals(0.05, product.q(1, 20, 20), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN the average combination (the alternative setting)
            WHEN q is computed
            THEN all at 20 gives 1 and all at 10 about 0.56
            """)
    void averageCombination() {
        assertEquals(1.0, average().q(20, 20, 20), 1e-9);
        assertEquals((0.5 + 1.0 / 1.5 + 0.5) / 3, average().q(10, 10, 10), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN the product combination (ordinary club q = 1/6)
            WHEN the decline multiplier is read
            THEN it is 1 at or above an ordinary club, 2 halfway down, 3 at q = 0
            """)
    void declineGrowsBelowAnOrdinaryClub() {
        assertEquals(1.0, product.declineMultiplier(0.5), 1e-9);
        assertEquals(1.0, product.declineMultiplier(1.0 / 6), 1e-9);
        assertEquals(2.0, product.declineMultiplier(1.0 / 12), 1e-9);
        assertEquals(3.0, product.declineMultiplier(0.0), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN a junior (mean 3 rises a month) trained at q = 1/6
            WHEN many months of growth are applied
            THEN he rises about 0.5 a month, a sixth of the full growth
            """)
    void growthIsScaledByQ() {
        MonthlyTrainingGrowth growth = new MonthlyTrainingGrowth(new MonthlyTrainingGrowth.Settings(
                MonthlyTrainingGrowth.Settings.defaults().bands(), 0.0, 5, 6, 2));
        Map<String, Integer> counters = new HashMap<>();
        TrainingType.TECHNICAL.attributes().forEach(name -> counters.put(name, 2));
        Random rng = new Random(3);
        long rises = 0;
        int months = 20_000;
        for (int i = 0; i < months; i++) {
            PlayerSkills skills = new PlayerSkills();
            PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(skills, 10));
            rises += growth.apply(skills, 17, counters, rng, name -> 1.0 / 6).rises().size();
        }
        assertEquals(0.5, rises / (double) months, 0.03);
    }

    @Test
    @DisplayName("""
            GIVEN a 24-year-old with Passing untrained for 3 months (5% a month normally)
            WHEN decline is applied with a multiplier of 3
            THEN Passing drops in about 15% of months
            """)
    void declineIsScaledByTheMultiplier() {
        MonthlyAttributeDecline decline = new MonthlyAttributeDecline(MonthlyAttributeDecline.Settings.defaults());
        Random rng = new Random(4);
        int drops = 0;
        int months = 20_000;
        for (int i = 0; i < months; i++) {
            PlayerSkills skills = new PlayerSkills();
            PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(skills, 10));
            if (decline.apply(skills, 24, Map.of("Passing", 3), rng, name -> 3.0).contains("Passing")) drops++;
        }
        assertEquals(0.15, drops / (double) months, 0.01);
    }

    @Test
    @DisplayName("""
            GIVEN the production application.yml
            WHEN simulation.training.quality is bound
            THEN the combination is product with the agreed steps
            """)
    void productionYamlBindsTheQualitySettings() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("app", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        TrainingQuality quality = Binder.get(environment)
                .bind("simulation.training.quality", TrainingQuality.class).get();

        assertEquals(TrainingQuality.Combination.PRODUCT, quality.getCombination());
        assertEquals(0.1, quality.getFacilityStep(), 1e-9);
        assertEquals(0.05, quality.getProfessionalismStep(), 1e-9);
        assertEquals(0.1, quality.getCoachStep(), 1e-9);
        assertEquals(3.0, quality.getMaxDeclineMultiplier(), 1e-9);
    }
}
