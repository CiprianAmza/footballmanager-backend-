package com.footballmanagergamesimulator.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Weights of the player's <em>rating</em>: the number the world sees (UI, scouting, transfers,
 * market value, AI decisions).
 *
 * <p>These weights are completely separate from the match engine's weights
 * ({@code compartment-scoring-weights-v1.yml}), which define the <em>special rating</em>. The
 * two never borrow from each other, so two players with the same rating can be worth different
 * amounts in a match.
 *
 * <p>Per position, a map of attribute name (as in {@code PlayerSkillsService.GETTER_MAP}, for
 * example {@code "Off The Ball"}) to a relative weight. Matching ignores case, spaces and
 * punctuation. A fine position (AMC, DM, AML, AMR, WBL, WBR) uses its base position's table
 * unless it is configured itself. Positions configured in {@code application.yml}
 * ({@code game.rating-weights.positions}) replace the built-in table for that position.
 */
@Configuration
@ConfigurationProperties(prefix = "game.rating-weights")
public class RatingWeightsConfig {

    private static volatile RatingWeightsConfig current = new RatingWeightsConfig();

    /** Configured tables, by position; each replaces the built-in table for that position. */
    private Map<String, Map<String, Double>> positions = new HashMap<>();

    @PostConstruct
    void publish() {
        current = this;
    }

    /** The live configuration; the built-in tables before Spring has started. */
    public static RatingWeightsConfig current() {
        return current;
    }

    /** Test seam: install an explicit configuration (null restores the built-in tables). */
    public static void install(RatingWeightsConfig config) {
        current = config == null ? new RatingWeightsConfig() : config;
    }

    public Map<String, Map<String, Double>> getPositions() {
        return positions;
    }

    public void setPositions(Map<String, Map<String, Double>> positions) {
        this.positions = positions == null ? new HashMap<>() : positions;
    }

    /**
     * The rating weights for a position, keyed by normalised attribute name
     * (see {@link #normalize}). Never empty.
     */
    public Map<String, Double> weightsFor(String position) {
        String pos = position == null ? "MC" : position.trim().toUpperCase(Locale.ROOT);
        String base = basePosition(pos);
        Map<String, Double> table = configured(pos);
        if (table == null) table = configured(base);
        if (table == null) table = DEFAULTS.get(pos);
        if (table == null) table = DEFAULTS.get(base);
        if (table == null) table = DEFAULTS.get("MC");
        return table;
    }

    private Map<String, Double> configured(String position) {
        Map<String, Double> raw = positions.get(position);
        if (raw == null || raw.isEmpty()) return null;
        Map<String, Double> normalized = new HashMap<>();
        raw.forEach((name, weight) -> {
            if (weight != null && weight > 0) normalized.put(normalize(name), weight);
        });
        return normalized.isEmpty() ? null : normalized;
    }

    /** Attribute-name key used for matching: lower case, letters only. */
    public static String normalize(String attributeName) {
        return attributeName == null ? "" : attributeName.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static String basePosition(String position) {
        return switch (position) {
            case "AMC", "DM" -> "MC";
            case "AML" -> "ML";
            case "AMR" -> "MR";
            case "WBL" -> "DL";
            case "WBR" -> "DR";
            default -> position;
        };
    }

    // ------------------------------------------------------------------ built-in tables
    // The hand-written per-position table that used to live in MatchEngineConfig as the
    // player-value weights. It now defines the rating only.

    private static final Map<String, Map<String, Double>> DEFAULTS = new HashMap<>();

    static {
        DEFAULTS.put("GK", table(
                "Reflexes", 5, "Handling", 5, "One On Ones", 4, "Positioning", 4,
                "Command Of Area", 4, "Anticipation", 4, "Concentration", 3, "Agility", 3,
                "Composure", 3, "Decisions", 3, "Kicking", 3, "Throwing", 2, "First Touch", 2,
                "Bravery", 2, "Jumping Reach", 2, "Passing", 1, "Vision", 1, "Determination", 1,
                "Leadership", 1, "Balance", 1, "Strength", 1, "Natural Fitness", 1));

        DEFAULTS.put("DC", table(
                "Tackling", 5, "Marking", 5, "Positioning", 5, "Heading", 4, "Strength", 4,
                "Anticipation", 4, "Concentration", 4, "Bravery", 3, "Jumping Reach", 3,
                "Composure", 3, "Decisions", 3, "Aggression", 2, "Pace", 2, "Acceleration", 2,
                "Passing", 2, "Determination", 2, "Leadership", 2, "Teamwork", 2, "Work Rate", 1,
                "Balance", 1, "Agility", 1, "Stamina", 1, "First Touch", 1, "Technique", 1,
                "Natural Fitness", 1));

        Map<String, Double> fullBack = table(
                "Pace", 5, "Tackling", 4, "Marking", 4, "Positioning", 4, "Acceleration", 4,
                "Stamina", 4, "Work Rate", 4, "Crossing", 4, "Anticipation", 3, "Concentration", 3,
                "Teamwork", 3, "Decisions", 3, "Dribbling", 3, "Passing", 3, "Technique", 2,
                "First Touch", 2, "Agility", 2, "Balance", 2, "Strength", 2, "Off The Ball", 2,
                "Determination", 2, "Aggression", 1, "Heading", 1, "Natural Fitness", 1,
                "Composure", 1, "Vision", 1);
        DEFAULTS.put("DL", fullBack);
        DEFAULTS.put("DR", fullBack);

        DEFAULTS.put("MC", table(
                "Passing", 5, "Decisions", 4, "Vision", 4, "Teamwork", 4, "Work Rate", 4,
                "Stamina", 4, "Technique", 3, "First Touch", 3, "Composure", 3, "Positioning", 3,
                "Anticipation", 3, "Concentration", 3, "Tackling", 3, "Determination", 3,
                "Dribbling", 2, "Off The Ball", 2, "Long Shots", 2, "Marking", 2, "Balance", 2,
                "Agility", 2, "Strength", 2, "Flair", 2, "Leadership", 2, "Aggression", 1,
                "Pace", 1, "Acceleration", 1));

        Map<String, Double> wide = table(
                "Crossing", 5, "Dribbling", 5, "Pace", 5, "Acceleration", 4, "Technique", 4,
                "Off The Ball", 4, "Stamina", 4, "First Touch", 3, "Flair", 3, "Agility", 3,
                "Work Rate", 3, "Passing", 3, "Decisions", 3, "Anticipation", 3, "Finishing", 3,
                "Long Shots", 2, "Balance", 2, "Composure", 2, "Teamwork", 2, "Determination", 2,
                "Vision", 2, "Marking", 1, "Tackling", 1, "Positioning", 1, "Strength", 1,
                "Concentration", 1);
        DEFAULTS.put("ML", wide);
        DEFAULTS.put("MR", wide);

        DEFAULTS.put("ST", table(
                "Finishing", 5, "Off The Ball", 5, "Composure", 4, "First Touch", 4,
                "Anticipation", 4, "Heading", 4, "Pace", 4, "Acceleration", 4, "Dribbling", 3,
                "Technique", 3, "Decisions", 3, "Strength", 3, "Flair", 3, "Balance", 3,
                "Agility", 3, "Jumping Reach", 3, "Long Shots", 3, "Determination", 3,
                "Bravery", 2, "Aggression", 2, "Teamwork", 2, "Work Rate", 2, "Passing", 2,
                "Vision", 2, "Stamina", 2, "Concentration", 2, "Penalty Taking", 2));
    }

    private static Map<String, Double> table(Object... nameWeight) {
        Map<String, Double> table = new HashMap<>();
        for (int i = 0; i < nameWeight.length; i += 2) {
            table.put(normalize((String) nameWeight[i]), ((Number) nameWeight[i + 1]).doubleValue());
        }
        return Map.copyOf(table);
    }
}
