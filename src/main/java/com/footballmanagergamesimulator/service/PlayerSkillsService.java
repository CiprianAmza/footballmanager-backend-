package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Service
public class PlayerSkillsService {

    // Getter and setter maps for all attributes (returns int, not long)
    public static final LinkedHashMap<String, Function<PlayerSkills, Integer>> GETTER_MAP = new LinkedHashMap<>();
    public static final LinkedHashMap<String, BiConsumer<PlayerSkills, Integer>> SETTER_MAP = new LinkedHashMap<>();

    // Category lists for grouping in UI
    public static final List<String> TECHNICAL = List.of(
            "Corners", "Crossing", "Dribbling", "Finishing", "First Touch", "Free Kick",
            "Heading", "Long Shots", "Long Throws", "Marking", "Passing", "Penalty Taking",
            "Tackling", "Ball Recovery", "Technique");

    public static final List<String> MENTAL = List.of(
            "Aggression", "Anticipation", "Bravery", "Composure", "Concentration",
            "Decisions", "Determination", "Flair", "Leadership", "Off The Ball",
            "Positioning", "Teamwork", "Vision", "Work Rate");

    public static final List<String> PHYSICAL = List.of(
            "Acceleration", "Agility", "Balance", "Jumping Reach", "Natural Fitness",
            "Pace", "Stamina", "Strength");

    public static final List<String> GOALKEEPER = List.of(
            "Handling", "Reflexes", "One On Ones", "Command Of Area", "Kicking", "Throwing");

    static {
        // Technical
        GETTER_MAP.put("Corners", PlayerSkills::getCorners);
        GETTER_MAP.put("Crossing", PlayerSkills::getCrossing);
        GETTER_MAP.put("Dribbling", PlayerSkills::getDribbling);
        GETTER_MAP.put("Finishing", PlayerSkills::getFinishing);
        GETTER_MAP.put("First Touch", PlayerSkills::getFirstTouch);
        GETTER_MAP.put("Free Kick", PlayerSkills::getFreeKick);
        GETTER_MAP.put("Heading", PlayerSkills::getHeading);
        GETTER_MAP.put("Long Shots", PlayerSkills::getLongShots);
        GETTER_MAP.put("Long Throws", PlayerSkills::getLongThrows);
        GETTER_MAP.put("Marking", PlayerSkills::getMarking);
        GETTER_MAP.put("Passing", PlayerSkills::getPassing);
        GETTER_MAP.put("Penalty Taking", PlayerSkills::getPenaltyTaking);
        GETTER_MAP.put("Tackling", PlayerSkills::getTackling);
        GETTER_MAP.put("Ball Recovery", PlayerSkills::getBallRecovery);
        GETTER_MAP.put("Technique", PlayerSkills::getTechnique);

        // Mental
        GETTER_MAP.put("Aggression", PlayerSkills::getAggression);
        GETTER_MAP.put("Anticipation", PlayerSkills::getAnticipation);
        GETTER_MAP.put("Bravery", PlayerSkills::getBravery);
        GETTER_MAP.put("Composure", PlayerSkills::getComposure);
        GETTER_MAP.put("Concentration", PlayerSkills::getConcentration);
        GETTER_MAP.put("Decisions", PlayerSkills::getDecisions);
        GETTER_MAP.put("Determination", PlayerSkills::getDetermination);
        GETTER_MAP.put("Flair", PlayerSkills::getFlair);
        GETTER_MAP.put("Leadership", PlayerSkills::getLeadership);
        GETTER_MAP.put("Off The Ball", PlayerSkills::getOffTheBall);
        GETTER_MAP.put("Positioning", PlayerSkills::getPositioning);
        GETTER_MAP.put("Teamwork", PlayerSkills::getTeamwork);
        GETTER_MAP.put("Vision", PlayerSkills::getVision);
        GETTER_MAP.put("Work Rate", PlayerSkills::getWorkRate);

        // Physical
        GETTER_MAP.put("Acceleration", PlayerSkills::getAcceleration);
        GETTER_MAP.put("Agility", PlayerSkills::getAgility);
        GETTER_MAP.put("Balance", PlayerSkills::getBalance);
        GETTER_MAP.put("Jumping Reach", PlayerSkills::getJumpingReach);
        GETTER_MAP.put("Natural Fitness", PlayerSkills::getNaturalFitness);
        GETTER_MAP.put("Pace", PlayerSkills::getPace);
        GETTER_MAP.put("Stamina", PlayerSkills::getStamina);
        GETTER_MAP.put("Strength", PlayerSkills::getStrength);

        // Goalkeeper
        GETTER_MAP.put("Handling", PlayerSkills::getHandling);
        GETTER_MAP.put("Reflexes", PlayerSkills::getReflexes);
        GETTER_MAP.put("One On Ones", PlayerSkills::getOneOnOnes);
        GETTER_MAP.put("Command Of Area", PlayerSkills::getCommandOfArea);
        GETTER_MAP.put("Kicking", PlayerSkills::getKicking);
        GETTER_MAP.put("Throwing", PlayerSkills::getThrowing);

        // Setters - Technical
        SETTER_MAP.put("Corners", PlayerSkills::setCorners);
        SETTER_MAP.put("Crossing", PlayerSkills::setCrossing);
        SETTER_MAP.put("Dribbling", PlayerSkills::setDribbling);
        SETTER_MAP.put("Finishing", PlayerSkills::setFinishing);
        SETTER_MAP.put("First Touch", PlayerSkills::setFirstTouch);
        SETTER_MAP.put("Free Kick", PlayerSkills::setFreeKick);
        SETTER_MAP.put("Heading", PlayerSkills::setHeading);
        SETTER_MAP.put("Long Shots", PlayerSkills::setLongShots);
        SETTER_MAP.put("Long Throws", PlayerSkills::setLongThrows);
        SETTER_MAP.put("Marking", PlayerSkills::setMarking);
        SETTER_MAP.put("Passing", PlayerSkills::setPassing);
        SETTER_MAP.put("Penalty Taking", PlayerSkills::setPenaltyTaking);
        SETTER_MAP.put("Tackling", PlayerSkills::setTackling);
        SETTER_MAP.put("Ball Recovery", PlayerSkills::setBallRecovery);
        SETTER_MAP.put("Technique", PlayerSkills::setTechnique);

        // Setters - Mental
        SETTER_MAP.put("Aggression", PlayerSkills::setAggression);
        SETTER_MAP.put("Anticipation", PlayerSkills::setAnticipation);
        SETTER_MAP.put("Bravery", PlayerSkills::setBravery);
        SETTER_MAP.put("Composure", PlayerSkills::setComposure);
        SETTER_MAP.put("Concentration", PlayerSkills::setConcentration);
        SETTER_MAP.put("Decisions", PlayerSkills::setDecisions);
        SETTER_MAP.put("Determination", PlayerSkills::setDetermination);
        SETTER_MAP.put("Flair", PlayerSkills::setFlair);
        SETTER_MAP.put("Leadership", PlayerSkills::setLeadership);
        SETTER_MAP.put("Off The Ball", PlayerSkills::setOffTheBall);
        SETTER_MAP.put("Positioning", PlayerSkills::setPositioning);
        SETTER_MAP.put("Teamwork", PlayerSkills::setTeamwork);
        SETTER_MAP.put("Vision", PlayerSkills::setVision);
        SETTER_MAP.put("Work Rate", PlayerSkills::setWorkRate);

        // Setters - Physical
        SETTER_MAP.put("Acceleration", PlayerSkills::setAcceleration);
        SETTER_MAP.put("Agility", PlayerSkills::setAgility);
        SETTER_MAP.put("Balance", PlayerSkills::setBalance);
        SETTER_MAP.put("Jumping Reach", PlayerSkills::setJumpingReach);
        SETTER_MAP.put("Natural Fitness", PlayerSkills::setNaturalFitness);
        SETTER_MAP.put("Pace", PlayerSkills::setPace);
        SETTER_MAP.put("Stamina", PlayerSkills::setStamina);
        SETTER_MAP.put("Strength", PlayerSkills::setStrength);

        // Setters - Goalkeeper
        SETTER_MAP.put("Handling", PlayerSkills::setHandling);
        SETTER_MAP.put("Reflexes", PlayerSkills::setReflexes);
        SETTER_MAP.put("One On Ones", PlayerSkills::setOneOnOnes);
        SETTER_MAP.put("Command Of Area", PlayerSkills::setCommandOfArea);
        SETTER_MAP.put("Kicking", PlayerSkills::setKicking);
        SETTER_MAP.put("Throwing", PlayerSkills::setThrowing);
    }

    /**
     * Compute overall rating (1-300 scale) from individual attributes based on position.
     * Each position weights different attributes to produce a meaningful overall score.
     *
     * <p>Scale rationale: a top-tier squad (team reputation ≈ 10,000) should
     * land top players in the 250-300 range — see Kvekrpur in
     * {@code BootstrapService} for the canonical reference player. Mid-table
     * squads land players 120-180, relegation-tier squads 30-80.
     */
    public static double computeOverallRating(PlayerSkills skills) {
        // The rating: what the world sees (UI, scouting, transfers, AI). It has its own
        // weights (RatingWeightsConfig), completely separate from the engine's weights that
        // define the special rating — see computeSpecialRating.
        Map<String, Double> weights = com.footballmanagergamesimulator.config.RatingWeightsConfig
                .current().weightsFor(normalizedPosition(skills));
        double weightTotal = 0;
        double weightedValue = 0;
        for (Map.Entry<String, Function<PlayerSkills, Integer>> entry : GETTER_MAP.entrySet()) {
            Double weight = weights.get(com.footballmanagergamesimulator.config.RatingWeightsConfig
                    .normalize(entry.getKey()));
            if (weight == null || weight <= 0) continue;
            int value = entry.getValue().apply(skills);
            weightedValue += weight * (value - RATING_ATTRIBUTE_MIN)
                    / (double) (RATING_ATTRIBUTE_MAX - RATING_ATTRIBUTE_MIN);
            weightTotal += weight;
        }
        if (weightTotal <= 0) return 1;
        // 19 in every weighted attribute = 300. The rating has no maximum: every point
        // above 19 keeps adding (20 > 19, 21 > 20).
        return Math.max(1, weightedValue / weightTotal * 300);
    }

    private static final int RATING_ATTRIBUTE_MIN = 1;
    private static final int RATING_ATTRIBUTE_MAX = 19;

    /**
     * The special rating: the player read through the weights the match engine scores with,
     * i.e. what he is really worth in a match at his position, independent of tactic. Same
     * 1&ndash;300 scale as {@link #computeOverallRating}, but completely separate weights.
     */
    public static double computeSpecialRating(PlayerSkills skills) {
        return computeSpecialRating(skills, skills.getPosition(), PositionAttributeImportance.current());
    }

    /**
     * The special rating of these attributes when played at {@code position} (which may
     * differ from the player's natural position), read through the given engine weights.
     */
    public static double computeSpecialRating(PlayerSkills skills, String position,
                                              PositionAttributeImportance table) {
        String pos = position == null ? "MC" : position.trim().toUpperCase(java.util.Locale.ROOT);
        if (table != null && table.knows(pos)) {
            return weightedRating(skills, table, table.importance(pos));
        }
        throw new IllegalStateException(
                "Engine weights are not installed; the special rating needs compartment-scoring-weights-v1.yml");
    }

    private static String normalizedPosition(PlayerSkills skills) {
        String pos = skills.getPosition();
        if (pos == null) pos = "MC";
        return pos.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Weighted average of the attributes this position is scored on, mapped to 1&ndash;300:
     * 19 in every weighted attribute = 300, the same scale as {@link #computeOverallRating}.
     *
     * <p>It used to be divided by the position's generation shape factor, back when generation
     * targeted this rating. Generation now targets the rating ({@code calibrateOverallRating}),
     * so that division only inflated the special rating (~450 for a 300-rated player).
     */
    private static double weightedRating(PlayerSkills skills,
                                         PositionAttributeImportance table,
                                         Map<com.footballmanagergamesimulator.compartment.PlayerAttribute, Double> importance) {
        double weightTotal = 0;
        double weightedValue = 0;
        int attributeMin = table.attributeMin();
        int attributeMax = table.attributeMax();
        double span = attributeMax - attributeMin;
        for (Map.Entry<com.footballmanagergamesimulator.compartment.PlayerAttribute, Double> entry
                : importance.entrySet()) {
            double weight = entry.getValue();
            if (weight <= 0) continue;
            weightTotal += weight;
            int raw = com.footballmanagergamesimulator.compartment.adapter.PlayerAttributeMapping
                    .rawValue(skills, entry.getKey());
            // Every point counts, also above attribute-max (19): 20 rates above 19.
            weightedValue += weight * ((raw - attributeMin) / span);
        }
        if (weightTotal <= 0) return 1;

        return Math.max(1, weightedValue / weightTotal * 300);
    }

    /**
     * g: moves only attributes that contribute to the positional overall until the computed
     * value is as close to {@code targetRating} as the integer 1-19 scale permits. Used for
     * generation: attributes above 19 are left untouched and none is pushed past 19.
     * Non-relevant attributes are left unchanged, preserving the position profile. There is
     * no rating maximum; a target beyond what 19s give is simply not reached.
     */
    public static double calibrateOverallRating(PlayerSkills skills, double targetRating) {
        return calibrate(skills, targetRating, null, null);
    }

    /**
     * Δ: moves only the given {@code attributes} until the rating is as close to
     * {@code targetRating} as the integer scale permits. Each point goes to an attribute
     * drawn at random (uniformly) among those of the set that still bring the rating closer.
     * Past 19 each level needs the rare roll of {@link AttributeCeilingOdds} (as in training);
     * a missed roll spends the point and that attribute grows no further in this growth, so the
     * rating may end below the target.
     */
    public static double calibrateOverallRating(PlayerSkills skills, double targetRating,
                                                Set<String> attributes, Random rng) {
        return calibrate(skills, targetRating, Objects.requireNonNull(attributes),
                Objects.requireNonNull(rng));
    }

    /**
     * {@code rng == null}: g (generation) — deterministic, always the step that lands
     * closest, never past 19 and never touching a 20+. Otherwise Δ — a random pick among
     * the allowed attributes that improve, each level past 19 behind the rare roll.
     */
    private static double calibrate(PlayerSkills skills, double targetRating,
                                    Set<String> allowed, Random rng) {
        double target = Math.max(1, targetRating);
        double current = computeOverallRating(skills);
        if (Double.compare(target, current) == 0) return current;

        double currentError = Math.abs(target - current);
        int ordinaryMax = 19;
        // One attribute moves one point per step; the bound only guards against a runaway loop.
        int maxSteps = SETTER_MAP.size() * 40;
        // Δ only: attributes whose rare roll past 19 failed in this growth.
        Set<String> blocked = new HashSet<>();

        for (int step = 0; step < maxSteps && currentError > 0.000001; step++) {
            int direction = Double.compare(target, current);
            List<String> improving = new ArrayList<>();
            List<Double> improvingRatings = new ArrayList<>();
            String bestAttribute = null;
            double bestRating = current;
            double bestError = currentError;

            for (Map.Entry<String, Function<PlayerSkills, Integer>> entry : GETTER_MAP.entrySet()) {
                String attribute = entry.getKey();
                if (allowed != null && !allowed.contains(attribute)) continue;
                if (blocked.contains(attribute)) continue;
                int originalValue = entry.getValue().apply(skills);
                int candidateValue = originalValue + direction;
                if (candidateValue < 1) continue;
                // Generation never creates or erases an exceptional (20+) ability.
                if (rng == null && (originalValue > ordinaryMax || candidateValue > ordinaryMax)) continue;

                BiConsumer<PlayerSkills, Integer> setter = SETTER_MAP.get(attribute);
                setter.accept(skills, candidateValue);
                double candidateRating = computeOverallRating(skills);
                setter.accept(skills, originalValue);

                double candidateError = Math.abs(target - candidateRating);
                if (candidateError + 0.000001 >= currentError) continue; // must get closer
                improving.add(attribute);
                improvingRatings.add(candidateRating);
                if (candidateError + 0.000001 < bestError) {
                    bestAttribute = attribute;
                    bestRating = candidateRating;
                    bestError = candidateError;
                }
            }

            if (improving.isEmpty()) break;
            if (rng != null) {
                int pick = rng.nextInt(improving.size());
                bestAttribute = improving.get(pick);
                bestRating = improvingRatings.get(pick);
                bestError = Math.abs(target - bestRating);
                int from = GETTER_MAP.get(bestAttribute).apply(skills);
                // Past 19 every level needs the same rare roll as training (design question 8, decided):
                // a missed roll spends the point and the attribute grows no further in this growth.
                if (direction > 0 && from + 1 > ordinaryMax && !AttributeCeilingOdds.reaches(from + 1, rng)) {
                    blocked.add(bestAttribute);
                    continue;
                }
            }
            int value = GETTER_MAP.get(bestAttribute).apply(skills);
            SETTER_MAP.get(bestAttribute).accept(skills, value + direction);
            current = bestRating;
            currentError = bestError;
        }

        return current;
    }
}
