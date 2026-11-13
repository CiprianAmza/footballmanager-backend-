package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTrainingCountersTest {

    private static final CompartmentEngineConfig CONFIG = load();
    private final AiTrainingCounters counters = new AiTrainingCounters(CONFIG);

    private static CompartmentEngineConfig load() {
        try {
            MutablePropertySources properties = new MutablePropertySources();
            new YamlPropertySourceLoader()
                    .load("weights", new ClassPathResource("compartment-scoring-weights-v1.yml"))
                    .forEach(properties::addLast);
            return new Binder(ConfigurationPropertySources.from(properties))
                    .bind("match.engine.compartment", Bindable.of(CompartmentEngineConfig.class))
                    .orElseThrow(() -> new IllegalStateException("compartment config is not bound"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> namesOf(Map<PlayerAttribute, Double> attributes) {
        return attributes.keySet().stream().map(AiTrainingCounters::displayName).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("""
            GIVEN AI players at ST, MC and DC
            WHEN their training counters are derived
            THEN each trains exactly the engine's attributes of his compartment: attack, midfield, defence
            """)
    void outfieldPlayersTrainTheirCompartment() {
        assertEquals(namesOf(CONFIG.getCompartments().get(Compartment.ATTACK).getAttributes()),
                counters.countersFor("ST").keySet());
        assertEquals(namesOf(CONFIG.getCompartments().get(Compartment.MIDFIELD).getAttributes()),
                counters.countersFor("MC").keySet());
        assertEquals(namesOf(CONFIG.getCompartments().get(Compartment.DEFENSE).getAttributes()),
                counters.countersFor("DC").keySet());
        assertTrue(counters.countersFor("ST").containsKey("Finishing"));
        assertTrue(counters.countersFor("DC").containsKey("Tackling"));
    }

    @Test
    @DisplayName("""
            GIVEN an AI goalkeeper
            WHEN his training counters are derived
            THEN he trains the goalkeeping set (Handling, Reflexes, One On Ones …), not an outfield compartment
            """)
    void goalkeepersTrainTheGoalkeepingSet() {
        Map<String, Integer> gk = counters.countersFor("GK");

        assertTrue(gk.keySet().containsAll(Set.of("Handling", "Reflexes", "One On Ones", "Command Of Area")));
        assertTrue(!gk.containsKey("Finishing") && !gk.containsKey("Tackling"));
    }

    @Test
    @DisplayName("""
            GIVEN every position in the engine config
            WHEN AI counters are derived
            THEN every attribute name is a real attribute and every counter is the same value
            """)
    void countersAreEqualAndNamesAreReal() {
        for (String position : CONFIG.getPositions().keySet()) {
            Map<String, Integer> result = counters.countersFor(position);
            assertTrue(!result.isEmpty(), position);
            assertTrue(PlayerSkillsService.GETTER_MAP.keySet().containsAll(result.keySet()), position + " " + result);
            assertTrue(result.values().stream().allMatch(value -> value == AiTrainingCounters.COUNTER), position);
        }
    }

    @Test
    @DisplayName("""
            GIVEN the engine's position weights
            WHEN the main compartment is picked
            THEN wingers attack, a defensive midfielder is a midfielder (1.08 over 1.05), full-backs defend
            """)
    void mainCompartmentIsWhereThePositionWeighsMost() {
        assertEquals(Compartment.ATTACK, counters.mainCompartment("AML"));
        assertEquals(Compartment.MIDFIELD, counters.mainCompartment("DM"));
        assertEquals(Compartment.DEFENSE, counters.mainCompartment("DL"));
        assertEquals(Compartment.MIDFIELD, counters.mainCompartment("UNKNOWN"));
    }
}
