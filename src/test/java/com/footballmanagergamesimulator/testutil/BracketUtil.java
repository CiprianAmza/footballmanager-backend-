package com.footballmanagergamesimulator.testutil;

import com.footballmanagergamesimulator.service.knockout.LegFormat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure bracket / seeding helpers shared by the cup, LoC, and Stars Cup outcome
 * tests: leg-format parsing, power-of-two bracket sizing, stage labels, the
 * group-of-4 double round-robin schedule, and power-based seeding comparators.
 *
 * <p>All methods are stateless and operate on {@link TeamSetup}.
 */
public final class BracketUtil {

    private BracketUtil() {}

    /**
     * Double round-robin schedule for a group of 4 (local indices 0..3),
     * 6 matchdays × 2 matches; {@code match[0]} is home.
     */
    public static final int[][][] GROUP_SCHEDULE = {
            {{0, 1}, {2, 3}},
            {{0, 2}, {1, 3}},
            {{0, 3}, {1, 2}},
            {{1, 0}, {3, 2}},
            {{2, 0}, {3, 1}},
            {{3, 0}, {2, 1}},
    };

    /** Parse {@code -Dleg.format}: "single"/"single-leg" (default) or "two"/"two-leg"/"home-away". */
    public static LegFormat parseLegFormat(String value) {
        if (value == null || value.isBlank()) return LegFormat.SINGLE_LEG;
        switch (value.trim().toLowerCase()) {
            case "single":
            case "single-leg":
            case "one":
                return LegFormat.SINGLE_LEG;
            case "two":
            case "two-leg":
            case "twoleg":
            case "home-away":
                return LegFormat.TWO_LEG;
            default:
                throw new IllegalArgumentException(
                        "Invalid -Dleg.format='" + value + "'. Use 'single' or 'two-leg'.");
        }
    }

    public static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    /** Bracket sizes from the opening round down to the final: [bracket, ..., 2]. */
    public static int[] stageSizes(int bracket) {
        List<Integer> sizes = new ArrayList<>();
        for (int s = bracket; s >= 2; s >>= 1) sizes.add(s);
        int[] out = new int[sizes.size()];
        for (int i = 0; i < out.length; i++) out[i] = sizes.get(i);
        return out;
    }

    /** Human-readable label for a bracket of {@code size} teams. */
    public static String stageLabel(int size) {
        switch (size) {
            case 2: return "Final";
            case 4: return "Semifinal";
            case 8: return "Quarterfinal";
            default: return "Round of " + size;
        }
    }

    /** Indices of {@code teams} sorted by power desc (then name asc) — strongest first. */
    public static Integer[] seedByPower(List<TeamSetup> teams) {
        Integer[] bySeed = new Integer[teams.size()];
        for (int i = 0; i < bySeed.length; i++) bySeed[i] = i;
        java.util.Arrays.sort(bySeed, powerDesc(teams));
        return bySeed;
    }

    /** Comparator on team indices: power desc, then name asc. */
    public static Comparator<Integer> powerDesc(List<TeamSetup> teams) {
        return Comparator.comparingDouble((Integer i) -> teams.get(i).power()).reversed()
                .thenComparing(i -> teams.get(i).name());
    }
}
