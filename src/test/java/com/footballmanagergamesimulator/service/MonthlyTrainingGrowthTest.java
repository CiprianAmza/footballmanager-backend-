package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonthlyTrainingGrowthTest {

    private static final int MONTHS = 20_000;

    private final MonthlyTrainingGrowth growth = new MonthlyTrainingGrowth(MonthlyTrainingGrowth.Settings.defaults());

    private static final MonthlyTrainingGrowth.Settings NO_INCREDIBLE_MONTH =
            new MonthlyTrainingGrowth.Settings(MonthlyTrainingGrowth.Settings.defaults().bands(), 0.0, 5, 6, 2);

    private PlayerSkills allAt(int value) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition("MC");
        PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(skills, value));
        return skills;
    }

    private static Map<String, Integer> trained(Object... nameThenCount) {
        Map<String, Integer> counters = new HashMap<>();
        for (int i = 0; i < nameThenCount.length; i += 2) {
            counters.put((String) nameThenCount[i], (Integer) nameThenCount[i + 1]);
        }
        return counters;
    }

    /** Eight trained attributes, all at 10, so the budget is never short of candidates. */
    private static final Map<String, Integer> EIGHT_TRAINED = trained(
            "Passing", 3, "Vision", 3, "Technique", 3, "Decisions", 3,
            "Stamina", 3, "Work Rate", 3, "Teamwork", 3, "Composure", 3);

    private double averageRises(MonthlyTrainingGrowth model, int age, long seed) {
        Random rng = new Random(seed);
        long total = 0;
        for (int i = 0; i < MONTHS; i++) {
            total += model.apply(allAt(10), age, EIGHT_TRAINED, rng).rises().size();
        }
        return total / (double) MONTHS;
    }

    @Test
    @DisplayName("""
            GIVEN a player who trained only Passing and Vision
            WHEN many months of growth are applied
            THEN no other attribute ever changes
            """)
    void onlyTrainedAttributesGrow() {
        Random rng = new Random(1);
        for (int i = 0; i < 2_000; i++) {
            PlayerSkills skills = allAt(10);
            MonthlyTrainingGrowth.Result result =
                    growth.apply(skills, 17, trained("Passing", 2, "Vision", 1), rng);
            assertTrue(List.of("Passing", "Vision").containsAll(result.rises()), "rises=" + result.rises());
            PlayerSkillsService.GETTER_MAP.forEach((name, getter) -> {
                if (!name.equals("Passing") && !name.equals("Vision")) assertEquals(10, getter.apply(skills), name);
            });
        }
    }

    @Test
    @DisplayName("""
            GIVEN players of every age band, outside an incredible month
            WHEN many months of growth are applied
            THEN the monthly rises average the band's mean, never exceed 2 x mean, and the young grow more often
            """)
    void monthlyBudgetFollowsAge() {
        Random rng = new Random(2);
        int[][] ageAndMax = {{17, 6}, {20, 5}, {23, 3}, {27, 1}, {32, 1}};
        MonthlyTrainingGrowth model = new MonthlyTrainingGrowth(NO_INCREDIBLE_MONTH);
        for (int[] band : ageAndMax) {
            for (int i = 0; i < 2_000; i++) {
                int rises = model.apply(allAt(10), band[0], EIGHT_TRAINED, rng).rises().size();
                assertTrue(rises <= band[1], "age " + band[0] + " rose " + rises);
            }
        }

        double at17 = averageRises(model, 17, 3);
        double at20 = averageRises(model, 20, 4);
        double at23 = averageRises(model, 23, 5);
        double at27 = averageRises(model, 27, 6);
        double at32 = averageRises(model, 32, 7);
        assertEquals(3.0, at17, 0.05);
        assertEquals(2.5, at20, 0.05);
        assertEquals(1.5, at23, 0.05);
        assertEquals(0.5, at27, 0.05);
        assertEquals(0.2, at32, 0.05);
        assertTrue(at17 > at20 && at20 > at23 && at23 > at27 && at27 > at32);
    }

    @Test
    @DisplayName("""
            GIVEN a player of 17 (mean 3 rises a month), outside an incredible month
            WHEN many months are applied
            THEN 3 is the most common count, with 2 and 4 in about 23% of months each: luck moves it both ways
            """)
    void monthlyCountVariesTightlyAroundTheMean() {
        MonthlyTrainingGrowth model = new MonthlyTrainingGrowth(NO_INCREDIBLE_MONTH);
        Random rng = new Random(12);
        int[] months = new int[7];
        for (int i = 0; i < MONTHS; i++) {
            months[model.apply(allAt(10), 17, EIGHT_TRAINED, rng).rises().size()]++;
        }
        assertEquals(0.3125, months[3] / (double) MONTHS, 0.015);
        assertEquals(0.2344, months[2] / (double) MONTHS, 0.015);
        assertEquals(0.2344, months[4] / (double) MONTHS, 0.015);
        assertEquals(0.0156, months[6] / (double) MONTHS, 0.005);
    }

    @Test
    @DisplayName("""
            GIVEN two attributes at the same value, one trained four times and one once
            WHEN many single rises are drawn
            THEN the more trained attribute is picked about four times as often
            """)
    void moreTrainingMeansMoreLikely() {
        assertEquals(4.0, pickRatio(trained("Passing", 4, "Vision", 1), 10, 10), 0.25);
    }

    @Test
    @DisplayName("""
            GIVEN two attributes trained equally, one at 5 and one at 15
            WHEN many single rises are drawn
            THEN the lower one is picked about three times as often, (20-5)/(20-15)
            """)
    void lowerValueMeansMoreLikely() {
        assertEquals(3.0, pickRatio(trained("Passing", 2, "Vision", 2), 5, 15), 0.2);
    }

    /** How often Passing rises relative to Vision when each month allows exactly one rise. */
    private double pickRatio(Map<String, Integer> counters, int passing, int vision) {
        MonthlyTrainingGrowth oneRise = new MonthlyTrainingGrowth(new MonthlyTrainingGrowth.Settings(
                List.of(new MonthlyTrainingGrowth.AgeBand(Integer.MAX_VALUE, 0.5)), 0.0, 5, 6, 2));
        Random rng = new Random(8);
        int passingRises = 0;
        int visionRises = 0;
        for (int i = 0; i < MONTHS; i++) {
            PlayerSkills skills = allAt(10);
            skills.setPassing(passing);
            skills.setVision(vision);
            List<String> rises = oneRise.apply(skills, 25, counters, rng).rises();
            if (rises.contains("Passing")) passingRises++;
            if (rises.contains("Vision")) visionRises++;
        }
        return passingRises / (double) visionRises;
    }

    @Test
    @DisplayName("""
            GIVEN a player whose only trained attribute is at 19
            WHEN he gets a rise every other month on average (at most one a month)
            THEN it reaches 20 in about 1 rise out of 100, i.e. 1 month out of 200
            """)
    void reachingTwentyIsRare() {
        MonthlyTrainingGrowth oneRise = new MonthlyTrainingGrowth(new MonthlyTrainingGrowth.Settings(
                List.of(new MonthlyTrainingGrowth.AgeBand(Integer.MAX_VALUE, 0.5)), 0.0, 5, 6, 2));
        Random rng = new Random(9);
        int reached = 0;
        int months = 200_000;
        for (int i = 0; i < months; i++) {
            PlayerSkills skills = allAt(10);
            skills.setPassing(19);
            if (!oneRise.apply(skills, 17, trained("Passing", 5), rng).rises().isEmpty()) {
                assertEquals(20, skills.getPassing());
                reached++;
            }
        }
        assertEquals(0.005, reached / (double) months, 0.001);
    }

    @Test
    @DisplayName("""
            GIVEN veterans of 32 who trained eight attributes
            WHEN many months are applied with the default settings
            THEN about 2% of months are incredible, each with 5-6 rises and no attribute rising more than twice
            """)
    void incredibleMonthsAreRareAndBig() {
        Random rng = new Random(10);
        int incredible = 0;
        for (int i = 0; i < MONTHS; i++) {
            MonthlyTrainingGrowth.Result result = growth.apply(allAt(10), 32, EIGHT_TRAINED, rng);
            if (!result.incredibleMonth()) {
                assertTrue(result.rises().size() <= 1);
                continue;
            }
            incredible++;
            assertTrue(result.rises().size() >= 5 && result.rises().size() <= 6, "rises=" + result.rises());
            Map<String, Integer> perAttribute = new HashMap<>();
            result.rises().forEach(name -> perAttribute.merge(name, 1, Integer::sum));
            assertTrue(perAttribute.values().stream().allMatch(count -> count <= 2), "rises=" + result.rises());
        }
        assertEquals(0.02, incredible / (double) MONTHS, 0.004);
    }

    @Test
    @DisplayName("""
            GIVEN a young player outside an incredible month
            WHEN growth is applied many times
            THEN an attribute never rises twice in the same month
            """)
    void anAttributeRisesAtMostOncePerOrdinaryMonth() {
        MonthlyTrainingGrowth model = new MonthlyTrainingGrowth(NO_INCREDIBLE_MONTH);
        Random rng = new Random(11);
        for (int i = 0; i < 5_000; i++) {
            List<String> rises = model.apply(allAt(10), 17, trained("Passing", 9, "Vision", 1), rng).rises();
            assertEquals(rises.stream().distinct().count(), rises.size(), "rises=" + rises);
        }
    }
}
