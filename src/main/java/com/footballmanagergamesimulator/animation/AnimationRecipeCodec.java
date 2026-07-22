package com.footballmanagergamesimulator.animation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON codec for {@link AnimationRecipe}. Kept as an explicit component so the
 * persisted format has a single owner and tests can assert
 * serialize → deserialize → identical frames.
 */
public final class AnimationRecipeCodec {

    private final ObjectMapper mapper;

    public AnimationRecipeCodec() {
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public String toJson(AnimationRecipe recipe) {
        try {
            return mapper.writeValueAsString(recipe);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize animation recipe " + recipe.key(), e);
        }
    }

    public AnimationRecipe fromJson(String json) {
        try {
            return mapper.readValue(json, AnimationRecipe.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot deserialize animation recipe", e);
        }
    }
}
