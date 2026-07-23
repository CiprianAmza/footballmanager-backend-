package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/** Loads the real Phase 0/1 catalogue and builds real (unpersisted) domain entities for adapter tests. */
final class AdapterTestFixture {

    private AdapterTestFixture() {}

    static CompartmentEngineConfig loadConfig() {
        try {
            MutablePropertySources properties = new MutablePropertySources();
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"))) {
                properties.addLast(source);
            }
            return new Binder(ConfigurationPropertySources.from(properties))
                    .bind("match.engine.compartment", Bindable.of(CompartmentEngineConfig.class))
                    .orElseThrow(() -> new IllegalStateException("compartment config is not bound"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load application.yml", e);
        }
    }

    /** A {@link PlayerSkills} with every mapped attribute set to {@code value}. */
    static PlayerSkills skillsAll(int value) {
        PlayerSkills s = new PlayerSkills();
        s.setFinishing(value);
        s.setOffTheBall(value);
        s.setDribbling(value);
        s.setPassing(value);
        s.setVision(value);
        s.setTechnique(value);
        s.setFirstTouch(value);
        s.setAcceleration(value);
        s.setPace(value);
        s.setComposure(value);
        s.setDecisions(value);
        s.setTeamwork(value);
        s.setWorkRate(value);
        s.setStamina(value);
        s.setPositioning(value);
        s.setAnticipation(value);
        s.setTackling(value);
        s.setMarking(value);
        s.setConcentration(value);
        s.setStrength(value);
        s.setHeading(value);
        s.setBravery(value);
        s.setJumpingReach(value);
        s.setHandling(value);
        s.setReflexes(value);
        s.setOneOnOnes(value);
        s.setCommandOfArea(value);
        s.setKicking(value);
        s.setThrowing(value);
        return s;
    }

    /**
     * A {@link PlayerSkills} whose every mapped field holds a distinct sentinel, plus the independent
     * expected map. Distinct values let the mapping test detect any field swap; values are raw (the
     * production {@code rawValue} does not clamp).
     */
    static Map.Entry<PlayerSkills, Map<PlayerAttribute, Integer>> skillsWithDistinctAttributes() {
        PlayerSkills s = new PlayerSkills();
        Map<PlayerAttribute, Integer> expected = new EnumMap<>(PlayerAttribute.class);
        int base = 101;
        s.setFinishing(base + 1);        expected.put(PlayerAttribute.FINISHING, base + 1);
        s.setOffTheBall(base + 2);       expected.put(PlayerAttribute.OFF_THE_BALL, base + 2);
        s.setDribbling(base + 3);        expected.put(PlayerAttribute.DRIBBLING, base + 3);
        s.setPassing(base + 4);          expected.put(PlayerAttribute.PASSING, base + 4);
        s.setVision(base + 5);           expected.put(PlayerAttribute.VISION, base + 5);
        s.setTechnique(base + 6);        expected.put(PlayerAttribute.TECHNIQUE, base + 6);
        s.setFirstTouch(base + 7);       expected.put(PlayerAttribute.FIRST_TOUCH, base + 7);
        s.setAcceleration(base + 8);     expected.put(PlayerAttribute.ACCELERATION, base + 8);
        s.setPace(base + 9);             expected.put(PlayerAttribute.PACE, base + 9);
        s.setComposure(base + 10);       expected.put(PlayerAttribute.COMPOSURE, base + 10);
        s.setDecisions(base + 11);       expected.put(PlayerAttribute.DECISIONS, base + 11);
        s.setTeamwork(base + 12);        expected.put(PlayerAttribute.TEAMWORK, base + 12);
        s.setWorkRate(base + 13);        expected.put(PlayerAttribute.WORK_RATE, base + 13);
        s.setStamina(base + 14);         expected.put(PlayerAttribute.STAMINA, base + 14);
        s.setPositioning(base + 15);     expected.put(PlayerAttribute.POSITIONING, base + 15);
        s.setAnticipation(base + 16);    expected.put(PlayerAttribute.ANTICIPATION, base + 16);
        s.setTackling(base + 17);        expected.put(PlayerAttribute.TACKLING, base + 17);
        s.setMarking(base + 18);         expected.put(PlayerAttribute.MARKING, base + 18);
        s.setConcentration(base + 19);   expected.put(PlayerAttribute.CONCENTRATION, base + 19);
        s.setStrength(base + 20);        expected.put(PlayerAttribute.STRENGTH, base + 20);
        s.setHeading(base + 21);         expected.put(PlayerAttribute.HEADING, base + 21);
        s.setBravery(base + 22);         expected.put(PlayerAttribute.BRAVERY, base + 22);
        s.setJumpingReach(base + 23);    expected.put(PlayerAttribute.JUMPING_REACH, base + 23);
        s.setHandling(base + 24);        expected.put(PlayerAttribute.HANDLING, base + 24);
        s.setReflexes(base + 25);        expected.put(PlayerAttribute.REFLEXES, base + 25);
        s.setOneOnOnes(base + 26);       expected.put(PlayerAttribute.ONE_ON_ONES, base + 26);
        s.setCommandOfArea(base + 27);   expected.put(PlayerAttribute.COMMAND_OF_AREA, base + 27);
        s.setKicking(base + 28);         expected.put(PlayerAttribute.KICKING, base + 28);
        s.setThrowing(base + 29);        expected.put(PlayerAttribute.THROWING, base + 29);
        return Map.entry(s, expected);
    }

    static Human human(long id, String position, double fitness, double morale) {
        Human h = new Human();
        h.setId(id);
        h.setPosition(position);
        h.setFitness(fitness);
        h.setMorale(morale);
        return h;
    }
}
