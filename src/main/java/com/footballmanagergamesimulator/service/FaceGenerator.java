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
    private static final int EYE_COLOR_COUNT = 4;
    /** Mouth has a single (human) catalog of this many shapes. */
    private static final int MOUTH_SHAPE_COUNT = 5;

    // Nose/eye catalogs split into a HUMAN region (low) + ALIEN region (high), matching the FE.
    private static final int NOSE_HUMAN = 5, NOSE_ALIEN = 5;   // noseShape 0-4 / 5-9
    private static final int EYE_HUMAN = 5, EYE_ALIEN = 5;     // eyeShape 0-4 / 5-9

    /** Per-nation chance (0..1) of drawing the ALIEN nose/eye region — Gallactick mostly alien. */
    private static double alienness(long nationId) {
        if (nationId == 1L) return 0.90; // Gallactick — alien athletes
        if (nationId == 0L) return 0.10; // International / continental
        return 0.04;                     // earthly nations — occasional exotic
    }

    // Per-nation STRUCTURAL identity (mirror of the FE player-face NATION_STRUCTURE table): the favored
    // pool of head silhouettes (faceShape 0-15) and hair silhouettes (hairStyle 0-19) per nation, so
    // each nation is recognizable by head + hair while keeping per-player variety within the pool.
    private static final Map<Long, int[]> NATION_HEAD = Map.of(
            0L, new int[]{0, 1, 2, 4},        // International
            1L, new int[]{5, 6, 7, 8, 9},     // Gallactick — alien skulls
            2L, new int[]{14, 3, 0, 11},      // Dong
            3L, new int[]{15, 2, 6, 12},      // Khess
            4L, new int[]{11, 10, 0, 1},      // FootieCup
            5L, new int[]{13, 4, 12, 1},      // Cards
            6L, new int[]{11, 13, 12, 15},    // Literature — distinctive non-oval (dome/tall/heart/hex)
            7L, new int[]{15, 13, 2, 6});     // Eleven
    private static final Map<Long, int[]> NATION_HAIR = Map.of(
            0L, new int[]{0, 1, 3, 5, 8},
            1L, new int[]{14, 15, 17, 18, 19},// Gallactick — exotic hair
            2L, new int[]{2, 13, 14, 4},
            3L, new int[]{14, 2, 15, 11},
            4L, new int[]{8, 1, 5, 17},
            5L, new int[]{0, 3, 6, 10},
            6L, new int[]{5, 7, 9, 10},
            7L, new int[]{3, 2, 11, 14});
    /** Per-nation favored eyebrow pool (browShape 0-8), mirror of the FE NATION_STRUCTURE brows. */
    private static final Map<Long, int[]> NATION_BROW = Map.of(
            0L, new int[]{0, 2, 4},
            1L, new int[]{4, 8, 1},
            2L, new int[]{3, 5, 7},
            3L, new int[]{6, 1, 5},
            4L, new int[]{7, 0, 5},
            5L, new int[]{4, 8, 2},
            6L, new int[]{8, 5, 2},
            7L, new int[]{5, 4, 6});
    private static final int[] DEFAULT_HEAD_POOL = {0, 1, 2, 4};
    private static final int[] DEFAULT_HAIR_POOL = {0, 1, 3, 5, 8};
    private static final int[] DEFAULT_BROW_POOL = {0, 2, 4};

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
        double al = alienness(nationId);
        // Hair + head silhouettes are drawn from the nation's favored pool (structural identity).
        player.setHairStyle(pickFrom(random, NATION_HAIR.getOrDefault(nationId, DEFAULT_HAIR_POOL)));
        player.setHairColor(weightedPick(random, HAIR_COLOR_WEIGHTS.getOrDefault(nationId, DEFAULT_HAIR)));
        player.setEyeColor(random.nextInt(EYE_COLOR_COUNT));
        player.setFaceShape(pickFrom(random, NATION_HEAD.getOrDefault(nationId, DEFAULT_HEAD_POOL)));
        // Nose/eye keep the human/alien split (Gallactick mostly alien via alienness).
        player.setNoseShape(pickShape(random, NOSE_HUMAN, NOSE_ALIEN, al));
        player.setEyeShape(pickShape(random, EYE_HUMAN, EYE_ALIEN, al));
        player.setMouthShape(random.nextInt(MOUTH_SHAPE_COUNT));
        player.setBrowShape(pickFrom(random, NATION_BROW.getOrDefault(nationId, DEFAULT_BROW_POOL)));
    }

    /** Uniformly pick one index from a favored pool. */
    private int pickFrom(Random random, int[] pool) {
        return pool[random.nextInt(pool.length)];
    }

    /** Pick a shape index: with probability {@code alienness} draw the ALIEN region
     *  [humanCount, humanCount+alienCount), otherwise the HUMAN region [0, humanCount). */
    private int pickShape(Random random, int humanCount, int alienCount, double alienness) {
        if (alienCount > 0 && random.nextDouble() < alienness) {
            return humanCount + random.nextInt(alienCount);
        }
        return random.nextInt(humanCount);
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
