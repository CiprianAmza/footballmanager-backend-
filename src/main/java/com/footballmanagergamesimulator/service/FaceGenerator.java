package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;

/**
 * Generates a deterministic, nation-weighted "face descriptor" for a player. The FE renders faces from
 * layered pieces (face shape, skin, hair, eyes) — the backend only stores compact integer indices.
 *
 * <p>Determinism: the descriptor is seeded purely from the player's id, so the same player always gets
 * the same face regardless of when generation runs. Nation only selects the weight tables (skin tone and
 * hair colour are the nation-defining axes), it does not enter the seed.
 *
 * <p>Index ranges (kept small and stable so the FE can map them to art):
 * baseFaceId 0-9, skinTone 0-5 (light->dark), hairStyle 0-9, hairColor 0-5
 * (0 black,1 dark brown,2 light brown,3 blonde,4 red,5 grey), eyeColor 0-3 (0 brown,1 hazel,2 green,3 blue).
 */
@Service
public class FaceGenerator {

    private static final int BASE_FACE_COUNT = 10;
    private static final int HAIR_STYLE_COUNT = 10;
    private static final int EYE_COLOR_COUNT = 4;
    /** Distinct shapes per facial component the FE can render (face/nose/eye/mouth). */
    private static final int SHAPE_COUNT = 5;

    /** Per-nation skin-tone weights over indices 0-5 (light -> dark). */
    private static final Map<Long, int[]> SKIN_TONE_WEIGHTS = Map.of(
            0L, new int[]{20, 25, 20, 15, 12, 8},   // Europe / mixed continental
            1L, new int[]{35, 30, 20, 10, 4, 1},    // Gallactick — pale
            2L, new int[]{2, 6, 14, 25, 28, 25},    // Dong — dark
            3L, new int[]{10, 18, 28, 24, 14, 6},   // Khess — mid
            4L, new int[]{30, 28, 22, 12, 6, 2},    // FootieCup — fair
            5L, new int[]{14, 20, 26, 22, 12, 6},   // Cards — mid
            6L, new int[]{25, 26, 22, 14, 8, 5},    // Literature — fair-mid
            7L, new int[]{5, 10, 18, 25, 24, 18});  // Eleven — dark-mid

    /** Per-nation hair-colour weights over indices 0-5. */
    private static final Map<Long, int[]> HAIR_COLOR_WEIGHTS = Map.of(
            0L, new int[]{30, 25, 18, 14, 6, 7},
            1L, new int[]{15, 22, 24, 28, 6, 5},    // Gallactick — lots of blonde
            2L, new int[]{55, 28, 10, 2, 1, 4},     // Dong — mostly black
            3L, new int[]{40, 30, 16, 6, 3, 5},     // Khess — dark
            4L, new int[]{18, 24, 24, 22, 7, 5},    // FootieCup — varied/blonde
            5L, new int[]{30, 28, 20, 12, 5, 5},
            6L, new int[]{22, 28, 22, 18, 5, 5},
            7L, new int[]{50, 30, 12, 3, 1, 4});    // Eleven — dark

    private static final int[] DEFAULT_SKIN = {17, 17, 17, 17, 16, 16};
    private static final int[] DEFAULT_HAIR = {17, 17, 17, 17, 16, 16};

    /**
     * Assign the face descriptor on {@code player} using the player's id as the deterministic seed and
     * {@code nationId} to pick the weighted skin/hair distributions. Idempotent for a given (id, nation).
     */
    public void assignFace(Human player, long nationId) {
        Random random = new Random(player.getId());

        // Draw order is fixed so the descriptor is reproducible from the seed.
        player.setBaseFaceId(random.nextInt(BASE_FACE_COUNT));
        player.setSkinTone(weightedPick(random, SKIN_TONE_WEIGHTS.getOrDefault(nationId, DEFAULT_SKIN)));
        player.setHairStyle(random.nextInt(HAIR_STYLE_COUNT));
        player.setHairColor(weightedPick(random, HAIR_COLOR_WEIGHTS.getOrDefault(nationId, DEFAULT_HAIR)));
        player.setEyeColor(random.nextInt(EYE_COLOR_COUNT));
        // Shape indices (each 0..SHAPE_COUNT-1) — drawn AFTER the colour/hair picks so previously
        // generated faces keep their colours; these add independent shape variety per player.
        player.setFaceShape(random.nextInt(SHAPE_COUNT));
        player.setNoseShape(random.nextInt(SHAPE_COUNT));
        player.setEyeShape(random.nextInt(SHAPE_COUNT));
        player.setMouthShape(random.nextInt(SHAPE_COUNT));
    }

    /** Pick an index in [0, weights.length) with probability proportional to its weight. */
    private int weightedPick(Random random, int[] weights) {
        int total = 0;
        for (int w : weights)
            total += w;
        int roll = random.nextInt(total);
        int cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (roll < cumulative)
                return i;
        }
        return weights.length - 1;
    }
}
