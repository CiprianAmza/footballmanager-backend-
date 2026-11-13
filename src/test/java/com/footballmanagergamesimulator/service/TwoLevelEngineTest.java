package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.ContextualPlayerRating;
import com.footballmanagergamesimulator.compartment.ContextualPlayerRatingCalculator;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerRatingInput;
import com.footballmanagergamesimulator.compartment.PlayerSpecialProfile;
import com.footballmanagergamesimulator.compartment.adapter.PlayerAttributeMapping;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
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
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The match engine in two levels: special profile (attributes, natural position) → tactic. */
class TwoLevelEngineTest {

    private PositionAttributeImportance previousTable;
    private CompartmentEngineConfig config;
    private ContextualPlayerRatingCalculator calculator;

    @BeforeEach
    void setUp() {
        previousTable = PositionAttributeImportance.current();
        config = loadEngineConfig();
        PositionAttributeImportance.install(config);
        calculator = new ContextualPlayerRatingCalculator(config);
    }

    @AfterEach
    void restore() {
        PositionAttributeImportance.restore(previousTable);
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

    private static PlayerSkills generated(String position, double rating, long seed) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition(position);
        new CompetitionService().generateSkills(skills, rating, new Random(seed));
        return skills;
    }

    private static PlayerRatingInput input(PlayerSkills skills, String played, Map<PlayerAttribute, Double> context) {
        return new PlayerRatingInput(played, "", Duty.SUPPORT, PlayerAttributeMapping.rawAttributeMap(skills),
                context, 1.0, 100, 70, 50, skills.getPosition());
    }

    @Test
    @DisplayName("""
            GIVEN generated players at GK, DC, MC and ST
            WHEN their special rating and their level-1 profile are computed
            THEN the special rating is the profile's attack / midfield / defense combined with the
             natural position's compartment shares, on the 1-300 scale
            """)
    void specialRatingIsTheCombinationOfTheProfile() {
        String[] positions = {"GK", "DC", "MC", "ST"};
        for (int i = 0; i < positions.length; i++) {
            PlayerSkills skills = generated(positions[i], 120 + 40 * i, i);
            PlayerSpecialProfile profile = calculator.profile(input(skills, positions[i], Map.of()));

            var shares = config.getPositions().get(positions[i]);
            double weighted = 0, total = 0;
            for (Compartment compartment : Compartment.values()) {
                weighted += shares.forCompartment(compartment) * profile.baseScore(compartment);
                total += shares.forCompartment(compartment);
            }
            double combined = weighted / total / config.getRating().getScoreScale();
            double expected = combined * 300;

            assertEquals(expected, PlayerSkillsService.computeSpecialRating(skills), 1e-9, positions[i]);
        }
    }

    @Test
    @DisplayName("""
            GIVEN a midfielder rated under two tactics that favour different attributes
            WHEN he is rated in each
            THEN his level-1 profile is the same in both, and only his value in the tactic differs
            """)
    void theTacticDoesNotChangeTheProfile() {
        PlayerSkills skills = generated("MC", 200, 3);
        Map<PlayerAttribute, Double> passing = new EnumMap<>(PlayerAttribute.class);
        passing.put(PlayerAttribute.PASSING, 1.0);
        passing.put(PlayerAttribute.VISION, 1.0);
        Map<PlayerAttribute, Double> running = new EnumMap<>(PlayerAttribute.class);
        running.put(PlayerAttribute.STAMINA, 1.0);
        running.put(PlayerAttribute.WORK_RATE, -1.0);

        PlayerRatingInput a = input(skills, "MC", passing);
        PlayerRatingInput b = input(skills, "MC", running);

        assertEquals(calculator.profile(a), calculator.profile(b));
        assertNotEquals(calculator.rate(a).compartments().get(Compartment.MIDFIELD).finalScore(),
                calculator.rate(b).compartments().get(Compartment.MIDFIELD).finalScore());
    }

    @Test
    @DisplayName("""
            GIVEN a natural goalkeeper played at DC and a natural centre-back played in goal
            WHEN they are rated
            THEN each one's defense starts from his own position's attribute table (keeper / outfield),
             while the position played sets the compartment multipliers
            """)
    void theProfileIsReadAtTheNaturalPosition() {
        PlayerSkills keeper = generated("GK", 200, 5);
        PlayerSkills defender = generated("DC", 200, 6);

        ContextualPlayerRating keeperAtDc = calculator.rate(input(keeper, "DC", Map.of()));
        ContextualPlayerRating defenderInGoal = calculator.rate(input(defender, "GK", Map.of()));

        assertEquals(calculator.profile(input(keeper, "GK", Map.of())).baseScore(Compartment.DEFENSE),
                keeperAtDc.compartments().get(Compartment.DEFENSE).baseScore(), 1e-9);
        assertEquals(calculator.profile(input(defender, "DC", Map.of())).baseScore(Compartment.DEFENSE),
                defenderInGoal.compartments().get(Compartment.DEFENSE).baseScore(), 1e-9);
        assertEquals(config.getPositions().get("GK").forCompartment(Compartment.DEFENSE),
                defenderInGoal.compartments().get(Compartment.DEFENSE).positionMultiplier(), 0.0);
    }

    @Test
    @DisplayName("""
            GIVEN players with every attribute at 19, at GK, DC, MC and ST
            WHEN both of their ratings are computed
            THEN both are 300: the special rating is on the same scale as the rating
            """)
    void bothRatingsShareTheScale() {
        for (String position : new String[]{"GK", "DC", "MC", "ST"}) {
            PlayerSkills skills = new PlayerSkills();
            skills.setPosition(position);
            PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(skills, 19));
            assertEquals(300.0, PlayerSkillsService.computeSpecialRating(skills), 1e-9, position);
            assertEquals(300.0, PlayerSkillsService.computeOverallRating(skills), 1e-9, position);
        }
    }
}
