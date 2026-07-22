package com.footballmanagergamesimulator.animation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class AnimationRecipeCodec {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public String encode(AnimationRecipe recipe) {
        try {
            return mapper.writeValueAsString(recipe);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot encode recipe " + recipe.key(), exception);
        }
    }

    public AnimationRecipe decode(String json) {
        try {
            return mapper.readValue(json, AnimationRecipe.class);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot decode animation recipe", exception);
        }
    }
}
