package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Training counters of an AI-managed player, derived on the update day instead of stored.
 *
 * <p>The AI coach is assumed to train only what matters in his team's play, compartment by
 * compartment: attackers the attack attributes, midfielders the midfield ones, defenders
 * the defence ones, goalkeepers the goalkeeping ones — the attribute sets of the match
 * engine ({@code compartment-scoring-weights-v1.yml}). A player belongs to the compartment
 * his position weighs most in. Every such attribute gets the same counter, so the choice
 * among them is driven only by how low each attribute still is.
 */
@Component
public class AiTrainingCounters {

    /** The counter every trained attribute of an AI player gets. Equal for all, so its size does not matter. */
    static final int COUNTER = 1;

    private final CompartmentEngineConfig config;

    public AiTrainingCounters(CompartmentEngineConfig config) {
        this.config = config;
    }

    /** Attribute name (as in {@link PlayerSkillsService#GETTER_MAP}) → counter, for a player at {@code position}. */
    public Map<String, Integer> countersFor(String position) {
        String pos = position == null ? "MC" : position.trim().toUpperCase(Locale.ROOT);
        Map<Compartment, CompartmentEngineConfig.CompartmentWeights> overrides =
                config.getPositionCompartmentOverrides().get(pos);
        Map<String, Integer> counters = new LinkedHashMap<>();
        if (overrides != null && !overrides.isEmpty()) {
            overrides.values().forEach(weights -> add(counters, weights));
            return counters;
        }
        add(counters, config.getCompartments().get(mainCompartment(pos)));
        return counters;
    }

    /** The compartment this position weighs most in (MC's if the position is unknown). */
    Compartment mainCompartment(String position) {
        CompartmentEngineConfig.CompartmentMultipliers m = config.getPositions().get(position);
        if (m == null) m = config.getPositions().get("MC");
        if (m == null) return Compartment.MIDFIELD;
        if (m.getAttack() >= m.getMidfield() && m.getAttack() >= m.getDefense()) return Compartment.ATTACK;
        if (m.getMidfield() >= m.getDefense()) return Compartment.MIDFIELD;
        return Compartment.DEFENSE;
    }

    private static void add(Map<String, Integer> counters, CompartmentEngineConfig.CompartmentWeights weights) {
        if (weights == null) return;
        for (PlayerAttribute attribute : weights.getAttributes().keySet()) {
            String name = displayName(attribute);
            if (PlayerSkillsService.GETTER_MAP.containsKey(name)) counters.put(name, COUNTER);
        }
    }

    /** FIRST_TOUCH → "First Touch", ONE_ON_ONES → "One On Ones". */
    static String displayName(PlayerAttribute attribute) {
        return Arrays.stream(attribute.name().split("_"))
                .map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }
}
