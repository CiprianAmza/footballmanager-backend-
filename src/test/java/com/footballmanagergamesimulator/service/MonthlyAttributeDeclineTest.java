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

class MonthlyAttributeDeclineTest {

    private static final int MONTHS = 40_000;

    private final MonthlyAttributeDecline decline =
            new MonthlyAttributeDecline(MonthlyAttributeDecline.Settings.defaults());

    private PlayerSkills allAt(int value) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition("MC");
        PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(skills, value));
        return skills;
    }

    /** Every outfield attribute untrained for {@code months}. */
    private Map<String, Integer> outfieldUntrainedFor(int months) {
        Map<String, Integer> untrained = new HashMap<>();
        PlayerSkillsService.GETTER_MAP.keySet().stream()
                .filter(name -> !PlayerSkillsService.GOALKEEPER.contains(name))
                .forEach(name -> untrained.put(name, months));
        return untrained;
    }

    /** How often {@code attribute} drops per month. */
    private double dropRate(String attribute, int age, Map<String, Integer> untrained, long seed) {
        Random rng = new Random(seed);
        int drops = 0;
        for (int i = 0; i < MONTHS; i++) {
            if (decline.apply(allAt(10), age, untrained, rng).contains(attribute)) drops++;
        }
        return drops / (double) MONTHS;
    }

    @Test
    @DisplayName("""
            GIVEN a 24-year-old whose attributes were all trained within the last 2 months
            WHEN many months of decline are applied
            THEN nothing ever drops
            """)
    void youngAndRecentlyTrainedNeverDrops() {
        Random rng = new Random(1);
        for (int i = 0; i < 5_000; i++) {
            assertTrue(decline.apply(allAt(10), 24, outfieldUntrainedFor(2), rng).isEmpty());
        }
    }

    @Test
    @DisplayName("""
            GIVEN a 24-year-old with Passing untrained for 3 months
            WHEN many months of decline are applied
            THEN Passing drops in about 5% of months, physical attributes trained recently never drop
            """)
    void longUntrainedAttributesDropRarely() {
        Map<String, Integer> untrained = outfieldUntrainedFor(0);
        untrained.put("Passing", 3);
        assertEquals(0.05, dropRate("Passing", 24, untrained, 2), 0.005);
        assertEquals(0.0, dropRate("Pace", 24, untrained, 3), 0.0);
    }

    @Test
    @DisplayName("""
            GIVEN a 29-year-old who trained everything this month
            WHEN many months of decline are applied
            THEN each physical attribute still drops in about 5% of months, and the rest never drop
            """)
    void fromTwentyEightPhysicalAttributesDropEvenWhenTrained() {
        Map<String, Integer> untrained = outfieldUntrainedFor(0);
        assertEquals(0.05, dropRate("Pace", 29, untrained, 4), 0.005);
        assertEquals(0.0, dropRate("Passing", 29, untrained, 5), 0.0);
    }

    @Test
    @DisplayName("""
            GIVEN 32-year-olds, one who never trains physical attributes and one who trains them every month
            WHEN many months of decline are applied
            THEN the first loses about 1.5 physical points a month, the second fewer but still some
            """)
    void afterThirtyPhysicalDeclineIsSlowedButNotStoppedByTraining() {
        double untrained = physicalDropsPerMonth(outfieldUntrainedFor(1), 6);
        double trained = physicalDropsPerMonth(outfieldUntrainedFor(0), 7);

        assertEquals(1.5, untrained, 0.05);
        assertEquals(0.75, trained, 0.05);
    }

    private double physicalDropsPerMonth(Map<String, Integer> untrained, long seed) {
        Random rng = new Random(seed);
        long drops = 0;
        for (int i = 0; i < MONTHS; i++) {
            drops += decline.apply(allAt(10), 32, untrained, rng).stream()
                    .filter(PlayerSkillsService.PHYSICAL::contains).count();
        }
        return drops / (double) MONTHS;
    }

    @Test
    @DisplayName("""
            GIVEN a 35-year-old with every attribute at 1 and untrained for a year
            WHEN many months of decline are applied
            THEN no attribute goes below 1, and each drop is a single point
            """)
    void neverBelowOneAndOnePointAtATime() {
        Random rng = new Random(8);
        for (int i = 0; i < 2_000; i++) {
            PlayerSkills skills = allAt(1);
            assertTrue(decline.apply(skills, 35, outfieldUntrainedFor(12), rng).isEmpty());
            PlayerSkillsService.GETTER_MAP.values().forEach(getter -> assertEquals(1, getter.apply(skills)));
        }
        for (int i = 0; i < 2_000; i++) {
            PlayerSkills skills = allAt(10);
            List<String> dropped = decline.apply(skills, 35, outfieldUntrainedFor(12), rng);
            assertEquals(dropped.stream().distinct().count(), dropped.size());
            dropped.forEach(name -> assertEquals(9, PlayerSkillsService.GETTER_MAP.get(name).apply(skills), name));
        }
    }

    @Test
    @DisplayName("""
            GIVEN a goalkeeper whose untrained map holds only goalkeeper and shared attributes
            WHEN many months of decline are applied
            THEN no attribute outside that map ever drops
            """)
    void onlyTheGivenAttributesCanDrop() {
        Map<String, Integer> untrained = new HashMap<>();
        PlayerSkillsService.GOALKEEPER.forEach(name -> untrained.put(name, 6));
        untrained.put("Pace", 6);
        Random rng = new Random(9);
        for (int i = 0; i < 5_000; i++) {
            List<String> dropped = decline.apply(allAt(10), 33, untrained, rng);
            assertTrue(untrained.keySet().containsAll(dropped), "dropped=" + dropped);
        }
    }
}
