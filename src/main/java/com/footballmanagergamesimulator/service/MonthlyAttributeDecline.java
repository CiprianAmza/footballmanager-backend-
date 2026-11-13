package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * What one month takes away, on a league's update day.
 *
 * <ul>
 *   <li><b>Any age:</b> an attribute not trained for {@code untrainedMonths} or more has
 *       {@code untrainedChance} a month to drop by 1.</li>
 *   <li><b>From {@code physicalFromAge} (28):</b> every physical attribute, trained or not,
 *       also has {@code physicalChance} a month to drop.</li>
 *   <li><b>From {@code oldAge} (31):</b> instead, physical attributes drop {@code oldPhysicalMean}
 *       a month on average (with luck, tightly around the mean), even when trained. Training
 *       slows this down without stopping it: a physical attribute trained this month resists
 *       a drop with {@code trainedResistChance}.</li>
 * </ul>
 * An attribute drops at most 1 a month and never below 1. Pure: the caller supplies how long
 * each attribute has gone untrained and saves the attributes.
 */
public final class MonthlyAttributeDecline {

    public record Settings(int untrainedMonths,
                           double untrainedChance,
                           int physicalFromAge,
                           double physicalChance,
                           int oldAge,
                           double oldPhysicalMean,
                           double trainedResistChance) {

        public static Settings defaults() {
            return new Settings(3, 0.05, 28, 0.05, 31, 1.5, 0.5);
        }
    }

    private static final Set<String> PHYSICAL = Set.copyOf(PlayerSkillsService.PHYSICAL);

    private final Settings settings;

    public MonthlyAttributeDecline(Settings settings) {
        this.settings = settings;
    }

    /**
     * Applies one month of decline to {@code skills}.
     *
     * @param monthsUntrained for each attribute the player can have (outfield or goalkeeper),
     *                        how many months since it was last trained; 0 = trained this month
     * @return the attributes that dropped by 1
     */
    public List<String> apply(PlayerSkills skills, int age, Map<String, Integer> monthsUntrained, Random rng) {
        return apply(skills, age, monthsUntrained, rng, attribute -> 1.0);
    }

    /**
     * Applies one month of decline, each chance (and the 31+ physical mean) multiplied by
     * {@code multiplierByAttribute} — stronger where training is poor
     * ({@link TrainingQuality#declineMultiplier}).
     */
    public List<String> apply(PlayerSkills skills, int age, Map<String, Integer> monthsUntrained, Random rng,
                              java.util.function.ToDoubleFunction<String> multiplierByAttribute) {
        Set<String> dropped = new HashSet<>();

        for (Map.Entry<String, Integer> entry : monthsUntrained.entrySet()) {
            String name = entry.getKey();
            if (!PlayerSkillsService.GETTER_MAP.containsKey(name)) continue;
            boolean longUntrained = entry.getValue() != null && entry.getValue() >= settings.untrainedMonths();
            if (longUntrained && rng.nextDouble() < settings.untrainedChance() * multiplierByAttribute.applyAsDouble(name)) dropped.add(name);
        }

        if (age >= settings.oldAge()) {
            List<String> physical = new ArrayList<>(monthsUntrained.keySet().stream()
                    .filter(PHYSICAL::contains).sorted().toList());
            double physicalMultiplier = physical.isEmpty() ? 1.0 : multiplierByAttribute.applyAsDouble(physical.get(0));
            int drops = MonthlyTrainingGrowth.drawAroundMean(settings.oldPhysicalMean() * physicalMultiplier, rng);
            for (int i = 0; i < drops && !physical.isEmpty(); i++) {
                String name = physical.remove(rng.nextInt(physical.size()));
                boolean trainedNow = Integer.valueOf(0).equals(monthsUntrained.get(name));
                if (trainedNow && rng.nextDouble() < settings.trainedResistChance()) continue;
                dropped.add(name);
            }
        } else if (age >= settings.physicalFromAge()) {
            for (String name : monthsUntrained.keySet()) {
                if (PHYSICAL.contains(name) && rng.nextDouble() < settings.physicalChance() * multiplierByAttribute.applyAsDouble(name)) dropped.add(name);
            }
        }

        List<String> applied = new ArrayList<>();
        for (String name : dropped.stream().sorted().toList()) {
            int value = PlayerSkillsService.GETTER_MAP.get(name).apply(skills);
            if (value <= 1) continue;
            PlayerSkillsService.SETTER_MAP.get(name).accept(skills, value - 1);
            applied.add(name);
        }
        return applied;
    }
}
