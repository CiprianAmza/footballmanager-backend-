package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * How much each attribute is worth to each position — derived from the weights the
 * match engine actually scores with, not authored separately.
 *
 * <p>The game used to hold three hand-written tables over the same 36 attributes:
 * one deciding what gets <em>generated</em> ({@code CompetitionService.getPositionProfile}),
 * one deciding what counts as <em>rating</em> ({@code PlayerSkillsService.dcRating} and
 * friends), and one deciding what wins <em>matches</em> (the compartment weights). They
 * disagreed, so a striker asked for 300 was generated with attributes his own rating
 * formula valued at 223, and two players on the same rating were worth different
 * amounts on the pitch. Rating measured one thing, the engine rewarded another, and
 * the transfer market optimised the first while the league table was decided by the
 * second.
 *
 * <p>This class removes two of the three tables. Importance is computed as
 *
 * <pre>raw(attribute, position) = Σ over compartments  positionMultiplier × attributeWeight</pre>
 *
 * so a striker's finishing is weighted by how much strikers contribute to ATTACK
 * ({@code 1.20}) times finishing's share of the ATTACK compartment ({@code 0.18}),
 * summed across all three compartments. Values are normalised so the position's most
 * valuable attribute is 1.0, which keeps the familiar "core = 1.0, minor = 0.3" shape
 * while making it a consequence of the engine rather than an opinion about it.
 *
 * <p>Generation multiplies these by the player's level; {@code computeOverallRating}
 * reads them back. The two are exact inverses, so a player asked for 300 is worth 300
 * both on his profile and to the engine that decides matches.
 */
@Component
public class PositionAttributeImportance {

    /** Base positions the rest of the game reasons in, plus the fine ones the config names. */
    private static final String FALLBACK_POSITION = "MC";

    /** Display-only floor; attributes at zero canonical weight never enter the rating. */
    private static final double MINIMUM_IMPORTANCE = 0.25;

    /** Normalized canonical weights. Zero means the match engine does not read the attribute. */
    private final Map<String, Map<PlayerAttribute, Double>> importanceByPosition = new HashMap<>();
    /** Archetype shape used only when generating attributes; display-only attributes may use the floor. */
    private final Map<String, Map<PlayerAttribute, Double>> generationImportanceByPosition = new HashMap<>();
    private final Map<String, Double> shapeFactorByPosition = new HashMap<>();

    private static volatile PositionAttributeImportance instance;

    @Autowired private CompartmentEngineConfig config;

    /**
     * Published statically because {@code PlayerSkillsService.computeOverallRating} is
     * static with 19 call sites, several of them outside Spring. Rather than thread a
     * bean through all of them, the table is installed once at startup and read from
     * there.
     */
    @PostConstruct
    void publish() {
        build();
        instance = this;
    }

    /** The live table, or {@code null} before Spring has started. */
    public static PositionAttributeImportance current() {
        return instance;
    }

    /** Test seam: install a table built from an explicit config. */
    public static PositionAttributeImportance install(CompartmentEngineConfig config) {
        PositionAttributeImportance table = new PositionAttributeImportance();
        table.config = config;
        table.build();
        instance = table;
        return table;
    }

    private void build() {
        importanceByPosition.clear();
        generationImportanceByPosition.clear();
        shapeFactorByPosition.clear();
        for (String position : config.getPositions().keySet()) {
            Map<PlayerAttribute, Double> raw = rawImportance(position);
            double peak = raw.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (peak <= 0) continue;

            Map<PlayerAttribute, Double> normalized = new EnumMap<>(PlayerAttribute.class);
            Map<PlayerAttribute, Double> generation = new EnumMap<>(PlayerAttribute.class);
            for (PlayerAttribute attribute : PlayerAttribute.values()) {
                double value = raw.getOrDefault(attribute, 0D) / peak;
                normalized.put(attribute, value);
                generation.put(attribute, Math.max(value, MINIMUM_IMPORTANCE));
            }
            importanceByPosition.put(position, normalized);
            generationImportanceByPosition.put(position, generation);

            // This is the canonical strength of the position archetype at rating 300.
            // Only weights actually read by Compartment V1 participate. Previously the
            // 0.25 display floor was also treated as a rating weight, so an outfielder's
            // low goalkeeper attributes could change his overall even though the engine
            // never read them.
            double weightTotal = 0, weightedShape = 0;
            for (Map.Entry<PlayerAttribute, Double> entry : normalized.entrySet()) {
                weightTotal += entry.getValue();
                weightedShape += entry.getValue() * generation.get(entry.getKey());
            }
            shapeFactorByPosition.put(position, weightTotal == 0 ? 1.0 : weightedShape / weightTotal);
        }
    }

    private Map<PlayerAttribute, Double> rawImportance(String position) {
        Map<PlayerAttribute, Double> raw = new EnumMap<>(PlayerAttribute.class);
        CompartmentEngineConfig.CompartmentMultipliers multipliers = config.getPositions().get(position);
        if (multipliers == null) return raw;

        for (Compartment compartment : Compartment.values()) {
            Map<PlayerAttribute, Double> weights = weightsFor(position, compartment);
            if (weights == null) continue;
            double share = multipliers.forCompartment(compartment);
            weights.forEach((attribute, weight) -> raw.merge(attribute, share * weight, Double::sum));
        }
        return raw;
    }

    /** A position-specific table wins over the shared one — this is how keepers get their own profile. */
    private Map<PlayerAttribute, Double> weightsFor(String position, Compartment compartment) {
        Map<Compartment, CompartmentEngineConfig.CompartmentWeights> overrides =
                config.getPositionCompartmentOverrides().get(position);
        if (overrides != null && overrides.containsKey(compartment)) {
            return overrides.get(compartment).getAttributes();
        }
        CompartmentEngineConfig.CompartmentWeights shared = config.getCompartments().get(compartment);
        return shared == null ? null : shared.getAttributes();
    }

    /** Importance of every attribute this position is scored on, peaking at 1.0. */
    public Map<PlayerAttribute, Double> importance(String position) {
        Map<PlayerAttribute, Double> table = importanceByPosition.get(normalize(position));
        return table == null ? Map.of() : table;
    }

    /** Attribute shape used by the inverse generator. It never defines what counts as rating. */
    public Map<PlayerAttribute, Double> generationImportance(String position) {
        Map<PlayerAttribute, Double> table = generationImportanceByPosition.get(normalize(position));
        return table == null ? Map.of() : table;
    }

    public int attributeMin() { return config.getRating().getAttributeMin(); }

    public int attributeMax() { return config.getRating().getAttributeMax(); }

    /**
     * How far a generated profile's weighted average sits below its level, because the
     * lesser attributes are scaled down. Rating divides by this so generation and rating
     * invert each other exactly.
     */
    public double shapeFactor(String position) {
        return shapeFactorByPosition.getOrDefault(normalize(position), 1.0);
    }

    public boolean knows(String position) {
        return importanceByPosition.containsKey(normalize(position));
    }

    private String normalize(String position) {
        if (position == null) return FALLBACK_POSITION;
        String trimmed = position.trim().toUpperCase(java.util.Locale.ROOT);
        return importanceByPosition.containsKey(trimmed) ? trimmed : FALLBACK_POSITION;
    }
}
