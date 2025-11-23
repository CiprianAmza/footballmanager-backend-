package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Scorer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Service
public class CompetitionService {

    public void generateSkills(PlayerSkills playerSkills, double rating) {

        Set<String> setters = PlayerSkillsService.SETTER_MAP.keySet();
        Random random = new Random();

        long total = 0L;

        for (Map.Entry<String, BiConsumer<PlayerSkills, Long>> setter: PlayerSkillsService.SETTER_MAP.entrySet()) {

            long randomSkill = random.nextLong(1L, 30L);
            setter.getValue().accept(playerSkills, randomSkill);

            total += randomSkill;
        }

        while (total > rating && Math.abs(total - rating) > 3) {

            for (Map.Entry<String, Function<PlayerSkills, Long>> getter: PlayerSkillsService.GETTER_MAP.entrySet()) {

                long value = getter.getValue().apply(playerSkills);
                String skillName = getter.getKey();

                if (value > 1 && total > rating) {
                    long upperBound = Math.min(value, (long) (total - rating));
                    if (upperBound <= 1) continue;
                    long randomSkill = random.nextLong(1L, upperBound);
                    long newValue = value - randomSkill;

                    PlayerSkillsService.SETTER_MAP.get(skillName).accept(playerSkills, newValue);
                    total -= randomSkill;
                }
            }
        }

        while (total < rating && Math.abs(total - rating) > 3) {

            for (Map.Entry<String, Function<PlayerSkills, Long>> getter: PlayerSkillsService.GETTER_MAP.entrySet()) {

                long value = getter.getValue().apply(playerSkills);
                String skillName = getter.getKey();

                if (value < 30 && total < rating) {
                    long upperBound = Math.min(30, (long) rating - total);
                    if (upperBound <= 1) continue;
                    long randomSkill = random.nextLong(1L, upperBound);
                    long newValue = value + randomSkill;

                    PlayerSkillsService.SETTER_MAP.get(skillName).accept(playerSkills, newValue);
                    total += randomSkill;
                }
            }
        }
    }

    // ideea e ca un ST sa aiba sanse mai mari sa dea gol decat un mijlocas
    public double getDifferentValueForScoringBasedOnPosition(Scorer scorer) {

        Map<String, Double> positionToValue = Map.of("GK", 0D,
                "DL", 1.3,
                "DR", 1.3,
                "DC", 1D,
                "ML", 2D,
                "MR", 2D,
                "MC", 1.7,
                "ST", 2.5);

        double totalRating = positionToValue.getOrDefault(scorer.getPosition(), 1D) * scorer.getRating();

        if (scorer.isSubstitute()) {
            totalRating /= 2;
        }

        return Math.max(totalRating, 0D);
    }
}
