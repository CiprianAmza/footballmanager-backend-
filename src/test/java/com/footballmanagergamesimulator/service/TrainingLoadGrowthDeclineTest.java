package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Training load (sessions trained ÷ default sessions) on the monthly growth budget and decline chances. */
class TrainingLoadGrowthDeclineTest {

    private static final int MONTHS = 20_000;
    private static final Map<String, Integer> EIGHT_TRAINED = Map.of(
            "Passing", 3, "Vision", 3, "Technique", 3, "Decisions", 3,
            "Stamina", 3, "Work Rate", 3, "Teamwork", 3, "Composure", 3);

    private final MonthlyTrainingGrowth growth = new MonthlyTrainingGrowth(MonthlyTrainingGrowth.Settings.defaults());
    private final MonthlyAttributeDecline decline = new MonthlyAttributeDecline(MonthlyAttributeDecline.Settings.defaults());

    @Test
    void fullLoadIsExactlyTheMonthWithoutALoad() {
        Random plain = new Random(42);
        Random loaded = new Random(42);
        for (int i = 0; i < 2_000; i++) {
            PlayerSkills a = allAt(10);
            PlayerSkills b = allAt(10);
            List<String> without = growth.apply(a, 19, EIGHT_TRAINED, plain, name -> 0.8).rises();
            List<String> with = growth.apply(b, 19, EIGHT_TRAINED, loaded, name -> 0.8, 1.0).rises();
            assertThat(with).isEqualTo(without);
        }
        assertThat(plain.nextLong()).isEqualTo(loaded.nextLong()); // not one extra draw
    }

    @Test
    void growthBudgetIsMultipliedByTheLoad() {
        double full = averageRises(1.0, 7);
        double twoThirds = averageRises(2.0 / 3.0, 7);
        double none = averageRises(0.0, 7);
        assertThat(twoThirds / full).isCloseTo(2.0 / 3.0, within(0.03));
        assertThat(none).isZero();
    }

    @Test
    void scaledBudgetKeepsAnExactMean() {
        Random rng = new Random(3);
        long total = 0;
        for (int i = 0; i < MONTHS; i++) total += MonthlyTrainingGrowth.scaled(3, 0.5, rng);
        assertThat(total / (double) MONTHS).isCloseTo(1.5, within(0.02));
        assertThat(MonthlyTrainingGrowth.scaled(3, 1.0, new Random(1))).isEqualTo(3);
    }

    @Test
    void declineAtFullLoadIsUnchangedAndAtHalfLoadIsHalfAgainAsLikely() {
        Map<String, Integer> untrained = new HashMap<>();
        for (String name : MonthlyTrainingUpdateService.relevantAttributes("MC")) untrained.put(name, 5);

        Random plain = new Random(9);
        Random loaded = new Random(9);
        for (int i = 0; i < 2_000; i++) {
            List<String> without = decline.apply(allAt(10), 26, untrained, plain, name -> 1.0);
            List<String> with = decline.apply(allAt(10), 26, untrained, loaded,
                    name -> 1.0 * FitnessEconomy.declineMultiplier(1.0));
            assertThat(with).isEqualTo(without);
        }

        double base = averageDrops(untrained, 1.0);
        double half = averageDrops(untrained, FitnessEconomy.declineMultiplier(0.5));
        assertThat(half / base).isCloseTo(1.5, within(0.05));
    }

    private double averageRises(double load, long seed) {
        Random rng = new Random(seed);
        long total = 0;
        for (int i = 0; i < MONTHS; i++) {
            total += growth.apply(allAt(10), 19, EIGHT_TRAINED, rng, name -> 1.0, load).rises().size();
        }
        return total / (double) MONTHS;
    }

    private double averageDrops(Map<String, Integer> untrained, double multiplier) {
        Random rng = new Random(5);
        long total = 0;
        for (int i = 0; i < MONTHS; i++) {
            total += decline.apply(allAt(10), 26, untrained, rng, name -> multiplier).size();
        }
        return total / (double) MONTHS;
    }

    private static PlayerSkills allAt(int value) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition("MC");
        PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(skills, value));
        return skills;
    }
}
