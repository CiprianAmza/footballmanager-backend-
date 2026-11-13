package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What one month of training grows, on a league's update day.
 *
 * <ol>
 *   <li><b>How many</b> attributes can grow: a monthly budget drawn from the player's age band
 *       (the younger, the more). Rarely (same odds at every age) the month is incredible:
 *       5&ndash;6 rises, and the same attribute may rise twice.</li>
 *   <li><b>Which:</b> only attributes trained since the last update. Each rise is drawn with
 *       weight {@code counter × (20 − value)}: trained often and still low → more likely. From
 *       19 up the weight is {@code counter × 1}, and reaching 20 and beyond needs the rare
 *       roll of {@link AttributeCeilingOdds}; a missed roll spends the rise.</li>
 *   <li>Each rise is +1.</li>
 * </ol>
 *
 * Pure: no persistence, the caller supplies the counters and saves the attributes.
 */
public final class MonthlyTrainingGrowth {

    /**
     * A budget band: players up to {@code maxAge} grow {@code mean} attributes a month on
     * average. The actual count varies with luck, tightly around the mean: it is the number
     * of successes in {@code ceil(2 × mean)} tries, each succeeding with
     * {@code mean / tries} (a mean of 3 gives 3 in 31% of months, 2 or 4 in 23% each,
     * 5 or 1 in 9%, 6 or 0 in 2%).
     */
    public record AgeBand(int maxAge, double mean) {

        int draw(Random rng) {
            return drawAroundMean(mean, rng);
        }
    }

    /**
     * A whole count that averages {@code mean} and varies tightly around it with luck: the
     * successes in {@code ceil(2 × mean)} tries, each succeeding with {@code mean / tries}.
     */
    static int drawAroundMean(double mean, Random rng) {
        if (mean <= 0) return 0;
        int tries = (int) Math.ceil(2 * mean);
        double chance = mean / tries;
        int count = 0;
        for (int i = 0; i < tries; i++) {
            if (rng.nextDouble() < chance) count++;
        }
        return count;
    }

    public record Settings(List<AgeBand> bands,
                           double incredibleMonthChance,
                           int incredibleMin,
                           int incredibleMax,
                           int incredibleRisesPerAttribute) {

        public static Settings defaults() {
            return new Settings(List.of(
                    new AgeBand(18, 3.0),
                    new AgeBand(21, 2.5),
                    new AgeBand(24, 1.5),
                    new AgeBand(29, 0.5),
                    new AgeBand(Integer.MAX_VALUE, 0.2)),
                    0.02, 5, 6, 2);
        }

        int budgetFor(int age, Random rng) {
            for (AgeBand band : bands) {
                if (age <= band.maxAge()) return band.draw(rng);
            }
            return 0;
        }
    }

    /** The attributes that rose (one entry per +1) and whether it was an incredible month. */
    public record Result(List<String> rises, boolean incredibleMonth) {}

    private final Settings settings;

    public MonthlyTrainingGrowth(Settings settings) {
        this.settings = settings;
    }

    /**
     * Applies one month of growth to {@code skills}.
     *
     * @param counters how many times each attribute was trained since the last update;
     *                 attributes absent or at 0 cannot grow
     */
    public Result apply(PlayerSkills skills, int age, Map<String, Integer> counters, Random rng) {
        return apply(skills, age, counters, rng, attribute -> 1.0);
    }

    /**
     * Applies one month of growth, where each rise of the budget only happens with the
     * training quality {@code q} of the chosen attribute's type ({@link TrainingQuality});
     * a rise that misses is spent. With q = 1 everywhere this is the full monthly growth.
     */
    public Result apply(PlayerSkills skills, int age, Map<String, Integer> counters, Random rng,
                        java.util.function.ToDoubleFunction<String> qByAttribute) {
        boolean incredible = rng.nextDouble() < settings.incredibleMonthChance();
        int budget = incredible
                ? settings.incredibleMin() + rng.nextInt(settings.incredibleMax() - settings.incredibleMin() + 1)
                : settings.budgetFor(age, rng);
        int perAttribute = incredible ? settings.incredibleRisesPerAttribute() : 1;

        List<String> rises = new ArrayList<>();
        Map<String, Integer> picked = new HashMap<>();
        for (int i = 0; i < budget; i++) {
            List<String> names = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            double total = 0;
            for (Map.Entry<String, Integer> counter : counters.entrySet()) {
                String name = counter.getKey();
                if (counter.getValue() == null || counter.getValue() <= 0) continue;
                if (!PlayerSkillsService.GETTER_MAP.containsKey(name)) continue;
                if (picked.getOrDefault(name, 0) >= perAttribute) continue;
                double weight = weight(counter.getValue(), value(skills, name));
                names.add(name);
                weights.add(weight);
                total += weight;
            }
            if (names.isEmpty()) break;

            String chosen = names.get(names.size() - 1);
            double roll = rng.nextDouble() * total;
            for (int k = 0; k < names.size(); k++) {
                roll -= weights.get(k);
                if (roll < 0) { chosen = names.get(k); break; }
            }
            picked.merge(chosen, 1, Integer::sum);

            double q = qByAttribute.applyAsDouble(chosen);
            if (q < 1.0 && rng.nextDouble() >= q) continue; // poorly trained: the rise is spent
            int next = value(skills, chosen) + 1;
            if (!AttributeCeilingOdds.reaches(next, rng)) continue; // the rise is spent
            PlayerSkillsService.SETTER_MAP.get(chosen).accept(skills, next);
            rises.add(chosen);
        }
        return new Result(rises, incredible);
    }

    static double weight(int counter, int value) {
        return counter * (double) Math.max(1, 20 - value);
    }

    private static int value(PlayerSkills skills, String name) {
        return PlayerSkillsService.GETTER_MAP.get(name).apply(skills);
    }
}
