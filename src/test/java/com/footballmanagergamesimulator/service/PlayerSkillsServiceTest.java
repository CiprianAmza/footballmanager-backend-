package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSkillsServiceTest {

    @Test
    void calibratesEveryPositionToTheRequestedMaximumRating() {
        CompetitionService competitionService = new CompetitionService();

        for (String position : List.of("GK", "DC", "DL", "MC", "MR", "ST", "AMC")) {
            PlayerSkills skills = new PlayerSkills();
            skills.setPosition(position);
            competitionService.generateSkills(skills, 300, new Random(42));

            double calibrated = PlayerSkillsService.calibrateOverallRating(skills, 300);

            assertEquals(300, calibrated, 0.000001, "position=" + position);
            assertEquals(300, PlayerSkillsService.computeOverallRating(skills), 0.000001,
                    "position=" + position);
            PlayerSkillsService.GETTER_MAP.forEach((attribute, getter) -> {
                int value = getter.apply(skills);
                assertTrue(value >= 1 && value <= 20,
                        () -> position + " " + attribute + "=" + value);
            });
        }
    }
}
