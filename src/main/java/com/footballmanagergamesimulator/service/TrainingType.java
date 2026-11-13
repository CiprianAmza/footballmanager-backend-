package com.footballmanagergamesimulator.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The kinds of training. Every player attribute belongs to exactly one type, and each type
 * is coached through two staff attributes: a coach's rating on a type is their average.
 * Attribute names are the keys of {@link PlayerSkillsService#GETTER_MAP}.
 */
public enum TrainingType {
    OFFENSIVE(Set.of("Finishing", "Long Shots", "Off The Ball", "Crossing", "Corners", "Free Kick", "Penalty Taking"),
            List.of("Attacking", "Set Pieces")),
    DEFENSIVE(Set.of("Marking", "Tackling", "Ball Recovery", "Heading", "Bravery", "Aggression"),
            List.of("Defending", "Duels")),
    TECHNICAL(Set.of("Dribbling", "First Touch", "Passing", "Technique", "Long Throws", "Flair"),
            List.of("Technical", "Ball Work")),
    MENTAL(Set.of("Composure", "Concentration", "Determination", "Leadership", "Work Rate"),
            List.of("Mental", "Motivating")),
    TACTICAL(Set.of("Anticipation", "Decisions", "Positioning", "Teamwork", "Vision"),
            List.of("Tactical", "Game Reading")),
    PHYSICAL(Set.of("Acceleration", "Agility", "Balance", "Jumping Reach", "Natural Fitness", "Pace", "Stamina", "Strength"),
            List.of("Fitness", "Conditioning")),
    GOALKEEPING(Set.of("Handling", "Reflexes", "One On Ones", "Command Of Area", "Kicking", "Throwing"),
            List.of("GK", "Shot-stopping"));

    private final Set<String> attributes;
    private final List<String> coachAttributes;

    TrainingType(Set<String> attributes, List<String> coachAttributes) {
        this.attributes = attributes;
        this.coachAttributes = coachAttributes;
    }

    /** The player attributes this type trains. */
    public Set<String> attributes() {
        return attributes;
    }

    /** The two staff attributes a coach is rated on for this type. */
    public List<String> coachAttributes() {
        return coachAttributes;
    }

    private static final Map<String, TrainingType> BY_ATTRIBUTE = new HashMap<>();

    static {
        for (TrainingType type : values()) {
            for (String attribute : type.attributes) BY_ATTRIBUTE.put(attribute, type);
        }
    }

    /** The type that trains {@code attribute}, or null for an unknown name. */
    public static TrainingType of(String attribute) {
        return BY_ATTRIBUTE.get(attribute);
    }
}
