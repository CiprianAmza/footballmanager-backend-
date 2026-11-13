package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRatingSyncTest {

    private final CompetitionService competitionService = new CompetitionService();

    private PlayerSkills skillsFor(String position, double rating, long seed) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition(position);
        competitionService.generateSkills(skills, rating, new Random(seed));
        PlayerSkillsService.calibrateOverallRating(skills, rating);
        return skills;
    }

    private Human player(double rating, int potential) {
        Human player = new Human();
        player.setRating(rating);
        player.setPotentialAbility(potential);
        player.setBestEverRating(rating);
        return player;
    }

    private Map<String, Integer> snapshot(PlayerSkills skills) {
        Map<String, Integer> values = new HashMap<>();
        PlayerSkillsService.GETTER_MAP.forEach((name, getter) -> values.put(name, getter.apply(skills)));
        return values;
    }

    @Test
    @DisplayName("""
            GIVEN a player whose stored rating disagrees with his attributes
            WHEN his rating is refreshed
            THEN the rating, current ability and best-ever rating come from the attributes
            """)
    void refreshDerivesTheRatingFromAttributes() {
        PlayerSkills skills = skillsFor("ST", 150, 1);
        Human player = player(10, 0);

        double rating = PlayerRatingSync.refresh(player, skills, 3);

        double expected = PlayerSkillsService.computeOverallRating(skills);
        assertEquals(expected, rating, 0.0);
        assertEquals(expected, player.getRating(), 0.0);
        assertEquals((int) expected, player.getCurrentAbility());
        assertEquals(expected, player.getBestEverRating(), 0.0);
        assertEquals(3, player.getSeasonOfBestEverRating());
    }

    @Test
    @DisplayName("""
            GIVEN a player whose potential is far below what his attributes are worth
            WHEN his rating is refreshed
            THEN the rating is not capped at the potential
            """)
    void potentialDoesNotCapTheRating() {
        PlayerSkills skills = skillsFor("MC", 160, 2);
        Human player = player(160, 60);

        PlayerRatingSync.refresh(player, skills, 1);

        assertTrue(player.getRating() > 60, "rating=" + player.getRating());
        assertEquals(PlayerSkillsService.computeOverallRating(skills), player.getRating(), 0.0);
    }

    @Test
    @DisplayName("""
            GIVEN a player rated about 120
            WHEN he is shifted up by 20 rating points
            THEN his attributes change, his rating lands near 140,
              and the rating still equals what the attributes produce
            """)
    void shiftMovesAttributesAndTheRatingFollows() {
        PlayerSkills skills = skillsFor("DC", 120, 3);
        Human player = player(0, 0);
        PlayerRatingSync.refresh(player, skills, 1);
        double before = player.getRating();
        Map<String, Integer> attributesBefore = snapshot(skills);

        PlayerRatingSync.shift(player, skills, 20, 2);

        assertNotEquals(attributesBefore, snapshot(skills));
        assertEquals(before + 20, player.getRating(), 5.0);
        assertEquals(PlayerSkillsService.computeOverallRating(skills), player.getRating(), 0.0);
        assertEquals(2, player.getSeasonOfBestEverRating());
    }

    @Test
    @DisplayName("""
            GIVEN a player rated about 200 with that as his best-ever rating
            WHEN he is shifted down by 25 rating points
            THEN his rating drops near 175 and his best-ever rating is kept
            """)
    void shiftDownKeepsTheBestEverRating() {
        PlayerSkills skills = skillsFor("GK", 200, 4);
        Human player = player(0, 0);
        PlayerRatingSync.refresh(player, skills, 1);
        double best = player.getBestEverRating();

        PlayerRatingSync.shift(player, skills, -25, 2);

        assertEquals(best - 25, player.getRating(), 5.0);
        assertEquals(best, player.getBestEverRating(), 0.0);
        assertEquals(1, player.getSeasonOfBestEverRating());
    }
}
