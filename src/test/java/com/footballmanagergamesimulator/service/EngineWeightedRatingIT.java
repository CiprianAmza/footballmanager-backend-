package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.ContextualPlayerRatingCalculator;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.PlayerRatingInput;
import com.footballmanagergamesimulator.compartment.adapter.PlayerAttributeMapping;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rating and generation must be inverses, and both must speak the engine's language.
 *
 * <p>Before this, generation and rating used different hand-written weight tables: asking
 * for a 300-rated striker produced attributes his own rating formula valued at 223, and a
 * keeper at 251 — the same request landing 26% low or 16% low depending on position.
 */
@SpringBootTest
class EngineWeightedRatingIT {

    @Autowired private CompetitionService competitionService;
    @Autowired private PositionAttributeImportance importance;
    @Autowired private CompartmentEngineConfig compartmentConfig;

    private static final String[] POSITIONS = {
            "GK", "DC", "DL", "DR", "WBL", "WBR", "DM", "MC", "ML", "MR",
            "AMC", "AML", "AMR", "ST"
    };

    @Test
    @DisplayName("a player generated at a rating is worth that rating")
    void generationRoundTripsThroughRating() {
        for (String position : POSITIONS) {
            for (double target : new double[]{300, 250, 200, 150, 100, 60}) {
                int samples = 60;
                for (int seed = 0; seed < samples; seed++) {
                    PlayerSkills skills = new PlayerSkills();
                    skills.setPosition(position);
                    competitionService.generateSkills(skills, target, new Random(seed));
                    double actual = PlayerSkillsService.computeOverallRating(skills);
                    assertThat(actual)
                            .as("%s asked for %.0f, seed %d", position, target, seed)
                            .isCloseTo(target, org.assertj.core.data.Offset.offset(0.75));
                }
            }
        }
    }

    @Test
    @DisplayName("each position's most valuable attributes are the ones the engine scores it on")
    void importanceFollowsTheEngine() {
        assertThat(topAttribute("ST")).isEqualTo(PlayerAttribute.FINISHING);
        assertThat(topAttribute("DC")).isIn(PlayerAttribute.TACKLING, PlayerAttribute.MARKING,
                PlayerAttribute.POSITIONING);
        assertThat(topAttribute("MC")).isIn(PlayerAttribute.PASSING, PlayerAttribute.VISION);
        assertThat(topAttribute("GK")).isIn(PlayerAttribute.HANDLING, PlayerAttribute.REFLEXES);
    }

    @Test
    @DisplayName("rating rises strictly with the attributes the engine reads")
    void ratingIsMonotonicInEngineAttributes() {
        PlayerSkills weak = new PlayerSkills();
        weak.setPosition("ST");
        competitionService.generateSkills(weak, 150, new Random(7));
        double before = PlayerSkillsService.computeOverallRating(weak);

        // Move only what the engine scores a striker on; the rating must follow.
        Map<PlayerAttribute, Double> table = importance.importance("ST");
        for (Map.Entry<PlayerAttribute, Double> entry : table.entrySet()) {
            if (entry.getValue() < 0.5) continue;
            int raw = com.footballmanagergamesimulator.compartment.adapter.PlayerAttributeMapping
                    .rawValue(weak, entry.getKey());
            com.footballmanagergamesimulator.compartment.adapter.PlayerAttributeMapping
                    .setValue(weak, entry.getKey(), Math.min(20, raw + 2));
        }
        assertThat(PlayerSkillsService.computeOverallRating(weak)).isGreaterThan(before);
    }

    @Test
    @DisplayName("canonical strength bands never overlap between generated ratings")
    void generatedRatingIsStrictlyMonotonicInCanonicalStrength() {
        ContextualPlayerRatingCalculator calculator = new ContextualPlayerRatingCalculator(compartmentConfig);
        for (String position : POSITIONS) {
            double previousMaximum = -1;
            for (double target : new double[]{60, 100, 150, 200, 250, 300}) {
                double minimum = Double.POSITIVE_INFINITY;
                double maximum = Double.NEGATIVE_INFINITY;
                for (int seed = 0; seed < 60; seed++) {
                    PlayerSkills skills = new PlayerSkills();
                    skills.setPosition(position);
                    competitionService.generateSkills(skills, target, new Random(seed));
                    var rating = calculator.rate(new PlayerRatingInput(
                            position, "", Duty.SUPPORT,
                            PlayerAttributeMapping.rawAttributeMap(skills), Map.of(),
                            1.0, 100.0, compartmentConfig.getRating().getMoraleNeutral(), 50.0));
                    double strength = rating.compartments().values().stream()
                            .mapToDouble(compartment -> compartment.finalScore()).sum();
                    minimum = Math.min(minimum, strength);
                    maximum = Math.max(maximum, strength);
                }
                assertThat(minimum)
                        .as("%s: weakest %.0f-rated player versus strongest lower-rated player",
                                position, target)
                        .isGreaterThan(previousMaximum);
                previousMaximum = maximum;
            }
        }
    }

    private PlayerAttribute topAttribute(String position) {
        return importance.importance(position).entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
    }
}
