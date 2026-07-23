package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.model.PlayerSkills;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Stable, explicit boundary mapping from each pure {@link PlayerAttribute} contract key to the
 * canonical {@link PlayerSkills} 1&ndash;20 field it is sourced from.
 *
 * <p>This is the single documented place where the pure Phase&nbsp;0/1 attribute vocabulary meets the
 * real {@code PlayerSkills} entity. Every one of the {@code 29} {@link PlayerAttribute} values is
 * mapped exactly once; a missing mapping is a programming error and fails fast. No clamping,
 * defaulting or JPA/lazy access happens here &mdash; only a scalar read of an already-loaded entity.
 */
public final class PlayerAttributeMapping {

    private static final Map<PlayerAttribute, ToIntFunction<PlayerSkills>> GETTERS =
            new EnumMap<>(PlayerAttribute.class);

    static {
        // Technical / mental / physical outfield attributes.
        GETTERS.put(PlayerAttribute.FINISHING, PlayerSkills::getFinishing);
        GETTERS.put(PlayerAttribute.OFF_THE_BALL, PlayerSkills::getOffTheBall);
        GETTERS.put(PlayerAttribute.DRIBBLING, PlayerSkills::getDribbling);
        GETTERS.put(PlayerAttribute.PASSING, PlayerSkills::getPassing);
        GETTERS.put(PlayerAttribute.VISION, PlayerSkills::getVision);
        GETTERS.put(PlayerAttribute.TECHNIQUE, PlayerSkills::getTechnique);
        GETTERS.put(PlayerAttribute.FIRST_TOUCH, PlayerSkills::getFirstTouch);
        GETTERS.put(PlayerAttribute.ACCELERATION, PlayerSkills::getAcceleration);
        GETTERS.put(PlayerAttribute.PACE, PlayerSkills::getPace);
        GETTERS.put(PlayerAttribute.COMPOSURE, PlayerSkills::getComposure);
        GETTERS.put(PlayerAttribute.DECISIONS, PlayerSkills::getDecisions);
        GETTERS.put(PlayerAttribute.TEAMWORK, PlayerSkills::getTeamwork);
        GETTERS.put(PlayerAttribute.WORK_RATE, PlayerSkills::getWorkRate);
        GETTERS.put(PlayerAttribute.STAMINA, PlayerSkills::getStamina);
        GETTERS.put(PlayerAttribute.POSITIONING, PlayerSkills::getPositioning);
        GETTERS.put(PlayerAttribute.ANTICIPATION, PlayerSkills::getAnticipation);
        GETTERS.put(PlayerAttribute.TACKLING, PlayerSkills::getTackling);
        GETTERS.put(PlayerAttribute.MARKING, PlayerSkills::getMarking);
        GETTERS.put(PlayerAttribute.CONCENTRATION, PlayerSkills::getConcentration);
        GETTERS.put(PlayerAttribute.STRENGTH, PlayerSkills::getStrength);
        GETTERS.put(PlayerAttribute.HEADING, PlayerSkills::getHeading);
        GETTERS.put(PlayerAttribute.BRAVERY, PlayerSkills::getBravery);
        GETTERS.put(PlayerAttribute.JUMPING_REACH, PlayerSkills::getJumpingReach);
        // Goalkeeper-specific attributes.
        GETTERS.put(PlayerAttribute.HANDLING, PlayerSkills::getHandling);
        GETTERS.put(PlayerAttribute.REFLEXES, PlayerSkills::getReflexes);
        GETTERS.put(PlayerAttribute.ONE_ON_ONES, PlayerSkills::getOneOnOnes);
        GETTERS.put(PlayerAttribute.COMMAND_OF_AREA, PlayerSkills::getCommandOfArea);
        GETTERS.put(PlayerAttribute.KICKING, PlayerSkills::getKicking);
        GETTERS.put(PlayerAttribute.THROWING, PlayerSkills::getThrowing);

        // Contract guard: every pure attribute key must be mapped exactly once.
        for (PlayerAttribute attribute : PlayerAttribute.values()) {
            if (!GETTERS.containsKey(attribute)) {
                throw new ExceptionInInitializerError("Unmapped PlayerAttribute: " + attribute);
            }
        }
    }

    private PlayerAttributeMapping() {}

    /** Raw 1&ndash;20 value for one attribute, read directly from the canonical entity (no clamp). */
    public static int rawValue(PlayerSkills skills, PlayerAttribute attribute) {
        ToIntFunction<PlayerSkills> getter = GETTERS.get(attribute);
        if (getter == null) {
            throw new IllegalStateException("No PlayerSkills mapping for " + attribute);
        }
        return getter.applyAsInt(skills);
    }

    /**
     * Full, faithful copy of every mapped attribute from the entity into an immutable-friendly map.
     * Values are raw (not yet clamped); defensive clamping is the adapter's config-aware concern.
     */
    public static Map<PlayerAttribute, Integer> rawAttributeMap(PlayerSkills skills) {
        Map<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute attribute : PlayerAttribute.values()) {
            attributes.put(attribute, rawValue(skills, attribute));
        }
        return attributes;
    }

    /** The complete set of mapped attribute keys (all {@link PlayerAttribute} values). */
    public static Set<PlayerAttribute> mappedAttributes() {
        return Collections.unmodifiableSet(GETTERS.keySet());
    }
}
