package com.footballmanagergamesimulator.matchplan;

import jakarta.persistence.*;

/**
 * Durable rendering recipe for one canonical goal animation.  The payload is the complete
 * versioned {@code GoalAnimationData} JSON produced when the goal was first shown.  Recovery
 * reads this payload instead of invoking whatever generator happens to be current later.
 */
@Entity
@Table(name = "match_animation_recipe",
        uniqueConstraints = @UniqueConstraint(columnNames = {
                "fixture_key", "slot_index"
        }))
public class MatchAnimationRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "fixture_key", nullable = false)
    private String fixtureKey;

    @Column(name = "slot_index", nullable = false)
    private int slotIndex;

    @Column(name = "generator_version", nullable = false)
    private int generatorVersion;

    @Column(name = "event_minute", nullable = false)
    private int minute;

    @Lob
    @Column(name = "recipe_json", nullable = false)
    private String recipeJson;

    protected MatchAnimationRecipe() {}

    public MatchAnimationRecipe(String fixtureKey, int slotIndex, int generatorVersion,
                                int minute, String recipeJson) {
        this.fixtureKey = fixtureKey;
        this.slotIndex = slotIndex;
        this.generatorVersion = generatorVersion;
        this.minute = minute;
        this.recipeJson = recipeJson;
    }

    public long getId() { return id; }
    public String getFixtureKey() { return fixtureKey; }
    public int getSlotIndex() { return slotIndex; }
    public int getGeneratorVersion() { return generatorVersion; }
    public int getMinute() { return minute; }
    public String getRecipeJson() { return recipeJson; }
}
