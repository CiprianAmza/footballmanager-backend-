package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryDeltaTest {

    private final CompetitionService competitionService = new CompetitionService();

    private PlayerSkills generated(String position, double rating, long seed) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition(position);
        competitionService.generateSkills(skills, rating, new Random(seed));
        PlayerSkillsService.calibrateOverallRating(skills, rating);
        return skills;
    }

    private PlayerSkills allAt(String position, int value) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition(position);
        PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(skills, value));
        return skills;
    }

    private Human player(PlayerSkills skills) {
        Human player = new Human();
        player.setRating(PlayerSkillsService.computeOverallRating(skills));
        player.setBestEverRating(player.getRating());
        return player;
    }

    private Map<String, Integer> snapshot(PlayerSkills skills) {
        Map<String, Integer> values = new HashMap<>();
        PlayerSkillsService.GETTER_MAP.forEach((name, getter) -> values.put(name, getter.apply(skills)));
        return values;
    }

    private Set<String> changed(Map<String, Integer> before, PlayerSkills after) {
        Map<String, Integer> now = snapshot(after);
        return before.keySet().stream()
                .filter(name -> !before.get(name).equals(now.get(name)))
                .collect(java.util.stream.Collectors.toSet());
    }

    /** The largest rating change one point of any of these attributes makes. */
    private double largestStep(PlayerSkills skills, Set<String> attributes) {
        double base = PlayerSkillsService.computeOverallRating(skills);
        double largest = 0;
        for (String name : attributes) {
            int value = PlayerSkillsService.GETTER_MAP.get(name).apply(skills);
            PlayerSkillsService.SETTER_MAP.get(name).accept(skills, value + 1);
            largest = Math.max(largest, Math.abs(PlayerSkillsService.computeOverallRating(skills) - base));
            PlayerSkillsService.SETTER_MAP.get(name).accept(skills, value);
        }
        return largest;
    }

    @Test
    @DisplayName("""
            GIVEN a striker rated about 150 with every attribute at 10 (room to grow below 19)
            WHEN he grows by 20 from the goals category
            THEN only goal attributes change and his rating rises by 20, as closely as one attribute point allows
            """)
    void goalsGrowthMovesOnlyGoalAttributesAndReachesTheTarget() {
        PlayerSkills skills = allAt("ST", 10);
        Human player = player(skills);
        double before = PlayerSkillsService.computeOverallRating(skills);
        Map<String, Integer> attributesBefore = snapshot(skills);
        double tolerance = largestStep(skills, StatCategory.GOALS.attributes()) / 2 + 1e-6;

        double after = PlayerRatingSync.shift(player, skills, 20, StatCategory.GOALS, new Random(1), 2);

        assertTrue(StatCategory.GOALS.attributes().containsAll(changed(attributesBefore, skills)),
                "changed=" + changed(attributesBefore, skills));
        assertEquals(before + 20, after, tolerance);
        assertEquals(after, player.getRating(), 0.0);
    }

    @Test
    @DisplayName("""
            GIVEN a goalkeeper rated about 150
            WHEN he grows by 15 from the saves category
            THEN only goalkeeping attributes change
            """)
    void savesGrowthMovesOnlyGoalkeepingAttributes() {
        PlayerSkills skills = generated("GK", 150, 12);
        Human player = player(skills);
        Map<String, Integer> attributesBefore = snapshot(skills);

        double after = PlayerRatingSync.shift(player, skills, 15, StatCategory.SAVES, new Random(2), 2);

        Set<String> moved = changed(attributesBefore, skills);
        assertTrue(!moved.isEmpty() && StatCategory.SAVES.attributes().containsAll(moved), "changed=" + moved);
        assertTrue(after > PlayerSkillsService.computeOverallRating(generated("GK", 150, 12)), "after=" + after);
    }

    @Test
    @DisplayName("""
            GIVEN centre-backs with every attribute at 19 (rating 300), for 20 different seeds
            WHEN each grows by 45 from the dribbles category (attributes that weigh little for a centre-back)
            THEN past 19 every level needs the rare roll (20: 1 in 100, 21: 1 in 500): no attribute goes beyond
             21, most stay at 19, and the rating falls far short of the 345 target instead of exploding
             (it used to push Technique to 190+)
            """)
    void seasonGrowthPastNineteenNeedsTheRareRoll() {
        int above19 = 0;
        for (long seed = 0; seed < 20; seed++) {
            PlayerSkills skills = allAt("DC", 19);
            Human player = player(skills);

            double after = PlayerRatingSync.shift(player, skills, 45, StatCategory.DRIBBLES, new Random(seed), 2);

            assertTrue(after < 310, "rating " + after);
            for (String name : StatCategory.DRIBBLES.attributes()) {
                int value = PlayerSkillsService.GETTER_MAP.get(name).apply(skills);
                assertTrue(value <= 21, name + " = " + value);
                if (value > 19) above19++;
            }
        }
        assertTrue(above19 <= 4, "rare: " + above19 + " of 80 attributes passed 19");
    }

    @Test
    @DisplayName("""
            GIVEN the same striker grown by 20 from the goals category with different random seeds
            WHEN the attribute changes are compared
            THEN the growth lands on different goal attributes, i.e. it is random rather than fixed
            """)
    void growthIsSpreadAtRandomAcrossTheCategory() {
        Set<Map<String, Integer>> outcomes = new java.util.HashSet<>();
        for (long seed = 0; seed < 10; seed++) {
            PlayerSkills skills = allAt("ST", 10);
            PlayerRatingSync.shift(player(skills), skills, 20, StatCategory.GOALS, new Random(seed), 2);
            outcomes.add(snapshot(skills));
        }

        assertTrue(outcomes.size() > 1, "every seed gave the same attributes");
    }

    @Test
    @DisplayName("""
            GIVEN a generated striker and one attribute already at 21
            WHEN he is calibrated (generation) to a rating far above 300
            THEN no attribute is pushed past 19 and the 21 is left alone
            """)
    void generationNeverPassesNineteenNorTouchesExceptionalAbilities() {
        PlayerSkills skills = generated("ST", 150, 13);
        skills.setFinishing(21);

        PlayerSkillsService.calibrateOverallRating(skills, 400);

        assertEquals(21, skills.getFinishing());
        snapshot(skills).forEach((name, value) -> {
            if (!name.equals("Finishing")) assertTrue(value <= 19, name + "=" + value);
        });
    }

    @Test
    @DisplayName("""
            GIVEN the ceiling odds table
            WHEN the odds for each level are read
            THEN up to 19 needs no roll, then 1/100, 1/500, 1/5,000, 1/10,000, and +5,000 per level
            """)
    void ceilingOddsFollowTheDesignTable() {
        assertEquals(1, AttributeCeilingOdds.oneIn(19));
        assertEquals(100, AttributeCeilingOdds.oneIn(20));
        assertEquals(500, AttributeCeilingOdds.oneIn(21));
        assertEquals(5_000, AttributeCeilingOdds.oneIn(22));
        assertEquals(10_000, AttributeCeilingOdds.oneIn(23));
        assertEquals(15_000, AttributeCeilingOdds.oneIn(24));
        assertEquals(20_000, AttributeCeilingOdds.oneIn(25));
        assertEquals(25_000, AttributeCeilingOdds.oneIn(26));
    }
}
