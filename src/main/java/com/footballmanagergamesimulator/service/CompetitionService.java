package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Scorer;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BiConsumer;

@Service
public class CompetitionService {

    /**
     * Generate realistic player attributes based on position and overall rating.
     * Each position has a profile defining which attributes should be high/medium/low.
     * The rating (1-100 scale) determines the base quality level, which maps to 1-20 attributes.
     *
     * Profile tiers for each attribute:
     *   HIGH   = core attribute for position (e.g., finishing for ST)
     *   MEDIUM = relevant but secondary (e.g., passing for ST)
     *   LOW    = not very relevant (e.g., marking for ST)
     */
    public void generateSkills(PlayerSkills playerSkills, double rating) {
        generateSkills(playerSkills, rating, new Random());
    }

    /**
     * Seeded variant — callers (e.g. {@code SquadGenerationService},
     * tests) can pass a deterministic {@link Random} so the generated
     * attributes are reproducible.
     */
    public void generateSkills(PlayerSkills playerSkills, double rating, Random random) {
        String position = playerSkills.getPosition();
        if (position == null) position = "MC";

        // Convert rating (1-300) to base attribute level (1-20).
        // rating 300 -> base ~20, rating 150 -> base ~10, rating 60 -> base ~4.
        double baseLevel = Math.max(1, Math.min(19, rating / 15.0));

        Map<String, Double> profile = getPositionProfile(position);

        for (Map.Entry<String, BiConsumer<PlayerSkills, Integer>> entry : PlayerSkillsService.SETTER_MAP.entrySet()) {
            String attrName = entry.getKey();
            double importance = profile.getOrDefault(attrName, 0.5); // default medium-low

            // importance: 1.0 = core attribute, 0.7 = secondary, 0.3 = minor
            // Spread: core attributes cluster near baseLevel, minor ones spread wider below
            double targetLevel = baseLevel * importance;

            // Add randomness: ±2 for variety
            double variance = random.nextGaussian() * 1.5;
            int value = (int) Math.round(targetLevel + variance);
            value = Math.max(1, Math.min(20, value));

            entry.getValue().accept(playerSkills, value);
        }

        // GK attributes: only high for goalkeepers, very low for outfield
        if (!"GK".equals(position)) {
            playerSkills.setHandling(random.nextInt(1, 4));
            playerSkills.setReflexes(random.nextInt(1, 4));
            playerSkills.setOneOnOnes(random.nextInt(1, 4));
            playerSkills.setCommandOfArea(random.nextInt(1, 4));
            playerSkills.setKicking(random.nextInt(1, 6));
            playerSkills.setThrowing(random.nextInt(1, 4));
        }
    }

    /**
     * Build a position-specific attribute importance profile.
     * Values: 1.0 = primary, 0.85 = strong, 0.7 = secondary, 0.5 = average, 0.3 = minor
     */
    private Map<String, Double> getPositionProfile(String position) {
        Map<String, Double> profile = new HashMap<>();

        // Default all to medium
        for (String attr : PlayerSkillsService.GETTER_MAP.keySet()) {
            profile.put(attr, 0.55);
        }

        switch (position) {
            case "GK" -> {
                // GK: goalkeeper attrs high, outfield low
                profile.put("Handling", 1.0);
                profile.put("Reflexes", 1.0);
                profile.put("One On Ones", 0.9);
                profile.put("Command Of Area", 0.9);
                profile.put("Kicking", 0.8);
                profile.put("Throwing", 0.75);
                profile.put("Positioning", 0.85);
                profile.put("Concentration", 0.8);
                profile.put("Anticipation", 0.8);
                profile.put("Composure", 0.75);
                profile.put("Agility", 0.8);
                profile.put("Decisions", 0.7);
                profile.put("Bravery", 0.7);
                // Low for outfield attrs
                profile.put("Dribbling", 0.2);
                profile.put("Finishing", 0.1);
                profile.put("Crossing", 0.1);
                profile.put("Tackling", 0.15);
                profile.put("Marking", 0.1);
                profile.put("Off The Ball", 0.1);
                profile.put("Long Shots", 0.1);
                profile.put("Heading", 0.2);
                profile.put("Flair", 0.2);
                profile.put("Vision", 0.25);
            }
            case "DC" -> {
                profile.put("Tackling", 1.0);
                profile.put("Marking", 0.95);
                profile.put("Positioning", 0.95);
                profile.put("Heading", 0.85);
                profile.put("Strength", 0.85);
                profile.put("Anticipation", 0.85);
                profile.put("Concentration", 0.85);
                profile.put("Bravery", 0.8);
                profile.put("Composure", 0.75);
                profile.put("Jumping Reach", 0.8);
                profile.put("Pace", 0.7);
                profile.put("Passing", 0.6);
                profile.put("Decisions", 0.7);
                profile.put("Teamwork", 0.7);
                profile.put("Aggression", 0.7);
                // Low
                profile.put("Finishing", 0.2);
                profile.put("Crossing", 0.25);
                profile.put("Dribbling", 0.3);
                profile.put("Flair", 0.2);
                profile.put("Off The Ball", 0.3);
                profile.put("Long Shots", 0.2);
            }
            case "DL", "DR" -> {
                profile.put("Tackling", 0.85);
                profile.put("Crossing", 0.85);
                profile.put("Pace", 0.9);
                profile.put("Stamina", 0.85);
                profile.put("Positioning", 0.8);
                profile.put("Marking", 0.75);
                profile.put("Anticipation", 0.75);
                profile.put("Work Rate", 0.85);
                profile.put("Dribbling", 0.7);
                profile.put("Passing", 0.7);
                profile.put("Acceleration", 0.85);
                profile.put("Concentration", 0.7);
                profile.put("Teamwork", 0.75);
                profile.put("First Touch", 0.65);
                profile.put("Decisions", 0.65);
                // Low
                profile.put("Finishing", 0.25);
                profile.put("Heading", 0.4);
                profile.put("Long Shots", 0.3);
                profile.put("Composure", 0.5);
            }
            case "MC" -> {
                profile.put("Passing", 0.95);
                profile.put("Vision", 0.85);
                profile.put("Decisions", 0.85);
                profile.put("Teamwork", 0.8);
                profile.put("First Touch", 0.8);
                profile.put("Technique", 0.8);
                profile.put("Stamina", 0.8);
                profile.put("Work Rate", 0.8);
                profile.put("Positioning", 0.75);
                profile.put("Tackling", 0.7);
                profile.put("Concentration", 0.7);
                profile.put("Composure", 0.75);
                profile.put("Anticipation", 0.7);
                profile.put("Dribbling", 0.65);
                profile.put("Long Shots", 0.65);
                // Lower
                profile.put("Heading", 0.4);
                profile.put("Crossing", 0.45);
                profile.put("Off The Ball", 0.5);
                profile.put("Marking", 0.5);
            }
            case "ML", "MR" -> {
                profile.put("Crossing", 0.9);
                profile.put("Dribbling", 0.9);
                profile.put("Pace", 0.9);
                profile.put("Acceleration", 0.85);
                profile.put("Technique", 0.8);
                profile.put("First Touch", 0.75);
                profile.put("Off The Ball", 0.75);
                profile.put("Flair", 0.7);
                profile.put("Passing", 0.7);
                profile.put("Stamina", 0.75);
                profile.put("Work Rate", 0.7);
                profile.put("Agility", 0.75);
                profile.put("Finishing", 0.65);
                profile.put("Decisions", 0.65);
                profile.put("Anticipation", 0.65);
                // Low
                profile.put("Tackling", 0.35);
                profile.put("Marking", 0.3);
                profile.put("Heading", 0.35);
                profile.put("Positioning", 0.5);
            }
            case "ST" -> {
                profile.put("Finishing", 1.0);
                profile.put("Off The Ball", 0.9);
                profile.put("Composure", 0.85);
                profile.put("First Touch", 0.8);
                profile.put("Dribbling", 0.75);
                profile.put("Heading", 0.7);
                profile.put("Pace", 0.75);
                profile.put("Acceleration", 0.7);
                profile.put("Anticipation", 0.75);
                profile.put("Technique", 0.7);
                profile.put("Strength", 0.7);
                profile.put("Decisions", 0.65);
                profile.put("Flair", 0.6);
                profile.put("Balance", 0.6);
                profile.put("Agility", 0.65);
                profile.put("Long Shots", 0.55);
                // Low
                profile.put("Tackling", 0.2);
                profile.put("Marking", 0.15);
                profile.put("Positioning", 0.4);
                profile.put("Crossing", 0.35);
                profile.put("Concentration", 0.45);
            }
        }

        return profile;
    }

    // ideea e ca un ST sa aiba sanse mai mari sa dea gol decat un mijlocas
    public double getDifferentValueForScoringBasedOnPosition(Scorer scorer) {

        // More skewed weights — strikers now dominate goal share like in real football,
        // defenders are very unlikely to score (matches reality of set-pieces/headers).
        // Quadratic on rating so a 90-rated striker towers over a 70-rated one
        // (allowing realistic hat-tricks from star players).
        Map<String, Double> positionToValue = Map.of("GK", 0D,
                "DL", 0.4,
                "DR", 0.4,
                "DC", 0.5,
                "ML", 1.6,
                "MR", 1.6,
                "MC", 1.8,
                "ST", 4.0);

        double posMul = positionToValue.getOrDefault(scorer.getPosition(), 0.8D);
        // Quadratic rating boost: rating² / 70 keeps a baseline-70 player ≈ unchanged
        // while a 90-rated player gets ~1.65x weight relative to baseline.
        double ratingFactor = (scorer.getRating() * scorer.getRating()) / 70D;
        double totalRating = posMul * ratingFactor;

        if (scorer.isSubstitute()) {
            totalRating /= 2;
        }

        return Math.max(totalRating, 0D);
    }
}
