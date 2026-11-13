package com.footballmanagergamesimulator.service;

import java.util.Set;

/**
 * A statistics category a player can excel in, and the attributes that grow when he does
 * (goals → finishing, long shots …). Attribute names are the keys of
 * {@link PlayerSkillsService#GETTER_MAP}.
 */
public enum StatCategory {
    GOALS("Finishing", "Long Shots", "Heading", "Composure", "Off The Ball"),
    ASSISTS("Passing", "Vision", "Crossing", "Technique"),
    DRIBBLES("Dribbling", "Technique", "Flair", "Agility"),
    TACKLES_AND_INTERCEPTIONS("Marking", "Tackling", "Anticipation", "Positioning", "Ball Recovery"),
    AERIAL_DUELS("Heading", "Jumping Reach", "Strength", "Bravery"),
    SAVES("Reflexes", "One On Ones", "Handling", "Command Of Area");

    private final Set<String> attributes;

    StatCategory(String... attributes) {
        this.attributes = Set.of(attributes);
    }

    public Set<String> attributes() {
        return attributes;
    }
}
