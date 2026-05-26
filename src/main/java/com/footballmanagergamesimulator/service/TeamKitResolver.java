package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData.TeamKit;
import com.footballmanagergamesimulator.model.Team;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the kit colors to use for a match animation. Two responsibilities:
 *   1. If both teams' primary outfield colors are too close visually, switch the
 *      defending team to its secondary so the player circles stay distinguishable.
 *   2. Pick goalkeeper kits that contrast against BOTH outfield kits — a yellow GK
 *      against a yellow team is the kind of bug we want to avoid.
 *
 * Colors arrive as CSS names ("blue", "darkblue", "white") from the team data,
 * so we map them onto a coarse "color family" enum and reason about that.
 */
@Service
public class TeamKitResolver {

    /** Coarse color buckets, enough to detect visual collisions without full hue math. */
    private enum Family {
        BLACK, WHITE, GREY,
        RED, ORANGE, YELLOW,
        GREEN, BLUE, PURPLE, PINK,
        UNKNOWN
    }

    /** Lookup: CSS-ish name → coarse family. Lowercased on input. */
    private static final Map<String, Family> FAMILY = new LinkedHashMap<>();
    static {
        FAMILY.put("black", Family.BLACK);
        FAMILY.put("white", Family.WHITE);
        FAMILY.put("grey", Family.GREY);
        FAMILY.put("gray", Family.GREY);
        FAMILY.put("silver", Family.GREY);
        FAMILY.put("red", Family.RED);
        FAMILY.put("darkred", Family.RED);
        FAMILY.put("crimson", Family.RED);
        FAMILY.put("orange", Family.ORANGE);
        FAMILY.put("darkorange", Family.ORANGE);
        FAMILY.put("yellow", Family.YELLOW);
        FAMILY.put("gold", Family.YELLOW);
        FAMILY.put("green", Family.GREEN);
        FAMILY.put("darkgreen", Family.GREEN);
        FAMILY.put("lime", Family.GREEN);
        FAMILY.put("blue", Family.BLUE);
        FAMILY.put("darkblue", Family.BLUE);
        FAMILY.put("navy", Family.BLUE);
        FAMILY.put("purple", Family.PURPLE);
        FAMILY.put("lila", Family.PURPLE); // dataset typo for "lilac" / "lavender"
        FAMILY.put("violet", Family.PURPLE);
        FAMILY.put("pink", Family.PINK);
    }

    /** GK kit candidates, ordered by visibility on a green pitch. */
    private static final List<String[]> GK_KITS = List.of(
            new String[]{"#fde047", "#ca8a04"}, // bright yellow
            new String[]{"#22d3ee", "#0e7490"}, // cyan
            new String[]{"#a855f7", "#6b21a8"}, // purple
            new String[]{"#f97316", "#9a3412"}, // orange
            new String[]{"#ec4899", "#9d174d"}, // pink
            new String[]{"#0f766e", "#134e4a"}  // dark teal
    );

    /** Resolve kits for both teams in one shot — handles primary-vs-primary clashes. */
    public TeamKit[] resolveKits(Team scoring, Team defending) {
        TeamKit scoringKit = buildOutfieldKit(scoring, /*useSecondary*/ false);

        boolean clash = familyOf(scoring.getColor1()) == familyOf(defending.getColor1());
        TeamKit defendingKit = buildOutfieldKit(defending, /*useSecondary*/ clash);

        // Pick GK kits AFTER outfield kits are settled, so we know what to avoid.
        scoringKit.setGkPrimary(pickGkPrimary(scoringKit.getOutfieldPrimary(), defendingKit.getOutfieldPrimary(), 0));
        scoringKit.setGkBorder(deriveGkBorder(scoringKit.getGkPrimary()));
        defendingKit.setGkPrimary(pickGkPrimary(scoringKit.getOutfieldPrimary(), defendingKit.getOutfieldPrimary(),
                indexOfKit(scoringKit.getGkPrimary()) + 1));
        defendingKit.setGkBorder(deriveGkBorder(defendingKit.getGkPrimary()));

        return new TeamKit[]{scoringKit, defendingKit};
    }

    private TeamKit buildOutfieldKit(Team team, boolean useSecondary) {
        TeamKit kit = new TeamKit();
        if (useSecondary) {
            // Swap so the defending team wears its alternate strip when clashing.
            kit.setOutfieldPrimary(orFallback(team.getColor2(), "#888"));
            kit.setOutfieldSecondary(orFallback(team.getColor1(), "#444"));
        } else {
            kit.setOutfieldPrimary(orFallback(team.getColor1(), "#3498db"));
            kit.setOutfieldSecondary(orFallback(team.getColor2(), "#2980b9"));
        }
        kit.setOutfieldBorder(orFallback(team.getBorder(), darken(kit.getOutfieldPrimary())));
        return kit;
    }

    /**
     * Pick the first GK kit (starting at {@code startIdx}) whose family doesn't match either
     * outfield color. Falls back to the first kit if every option clashes.
     */
    private String pickGkPrimary(String outfieldA, String outfieldB, int startIdx) {
        Family fa = familyOf(outfieldA);
        Family fb = familyOf(outfieldB);
        for (int i = 0; i < GK_KITS.size(); i++) {
            int idx = (startIdx + i) % GK_KITS.size();
            String candidate = GK_KITS.get(idx)[0];
            Family fc = familyOf(candidate);
            if (fc != fa && fc != fb) return candidate;
        }
        return GK_KITS.get(0)[0];
    }

    private int indexOfKit(String primary) {
        for (int i = 0; i < GK_KITS.size(); i++) {
            if (GK_KITS.get(i)[0].equalsIgnoreCase(primary)) return i;
        }
        return 0;
    }

    private String deriveGkBorder(String primary) {
        return GK_KITS.stream()
                .filter(arr -> arr[0].equalsIgnoreCase(primary))
                .map(arr -> arr[1])
                .findFirst()
                .orElse(darken(primary));
    }

    private Family familyOf(String color) {
        if (color == null) return Family.UNKNOWN;
        String key = color.trim().toLowerCase();
        if (key.startsWith("#")) return familyOfHex(key);
        return FAMILY.getOrDefault(key, Family.UNKNOWN);
    }

    /** Cheap hex → family classifier; only used for our own GK palette and any DB hex values. */
    private Family familyOfHex(String hex) {
        if (hex.length() != 7) return Family.UNKNOWN;
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            if (max < 60) return Family.BLACK;
            if (min > 200) return Family.WHITE;
            if (max - min < 30) return Family.GREY;
            if (r > g && r > b) {
                if (g > 150) return Family.YELLOW;
                if (g > 100) return Family.ORANGE;
                if (b > 100) return Family.PINK;
                return Family.RED;
            }
            if (g > r && g > b) return Family.GREEN;
            if (b > r && b > g) {
                if (r > 150) return Family.PURPLE;
                return Family.BLUE;
            }
            return Family.UNKNOWN;
        } catch (NumberFormatException e) {
            return Family.UNKNOWN;
        }
    }

    private String orFallback(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /** Heuristic dark border for a named color when the DB doesn't carry one. */
    private String darken(String color) {
        if (color == null) return "#000";
        String c = color.toLowerCase();
        // Just provide reasonable named-color dark border counterparts; the canvas
        // accepts both names and hex, so we lean on the browser when possible.
        return switch (c) {
            case "white" -> "#bbb";
            case "yellow", "gold" -> "#a16207";
            case "orange" -> "#9a3412";
            case "red" -> "#7f1d1d";
            case "pink" -> "#9d174d";
            case "green", "lime" -> "#14532d";
            case "blue" -> "#1e3a8a";
            case "purple", "lila", "violet" -> "#581c87";
            case "black" -> "#222";
            case "grey", "gray", "silver" -> "#555";
            default -> "#222";
        };
    }

    /** Exposed for tests / debugging — list of GK kit primaries the resolver may pick from. */
    public List<String> gkKitPrimaries() {
        return Arrays.stream(GK_KITS.toArray(new String[0][]))
                .map(arr -> arr[0])
                .toList();
    }
}
