package com.footballmanagergamesimulator.service;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TacticService {

    // All formation definitions: position code -> count for auto best-eleven selection
    private static final Map<String, Map<String, Integer>> FORMATIONS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Integer>> SUBSTITUTION_FORMATS = new LinkedHashMap<>();

    static {
        // --- Existing formations ---
        FORMATIONS.put("442",  Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 2, "MR", 1, "ST", 2));
        FORMATIONS.put("433",  Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 1, "MR", 1, "ST", 3));
        FORMATIONS.put("343",  Map.of("GK", 1, "DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 2, "MR", 1, "ST", 3));
        FORMATIONS.put("451",  Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 3, "MR", 1, "ST", 1));
        FORMATIONS.put("352",  Map.of("GK", 1, "DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 3, "MR", 1, "ST", 2));

        // --- New formations ---
        FORMATIONS.put("4231", Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 3, "MR", 1, "ST", 1));
        FORMATIONS.put("4141", Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 3, "MR", 1, "ST", 1));
        FORMATIONS.put("4411", Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 2, "MR", 1, "ST", 2));
        FORMATIONS.put("4321", Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 1, "MC", 2, "MR", 1, "ST", 2));
        FORMATIONS.put("4222", Map.of("GK", 1, "DL", 1, "DC", 2, "DR", 1, "ML", 0, "MC", 4, "MR", 0, "ST", 2));
        FORMATIONS.put("3421", Map.of("GK", 1, "DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 4, "MR", 1, "ST", 1));
        FORMATIONS.put("532",  Map.of("GK", 1, "DL", 1, "DC", 3, "DR", 1, "ML", 0, "MC", 3, "MR", 0, "ST", 2));
        FORMATIONS.put("5212", Map.of("GK", 1, "DL", 1, "DC", 3, "DR", 1, "ML", 0, "MC", 3, "MR", 0, "ST", 2));
        FORMATIONS.put("541",  Map.of("GK", 1, "DL", 1, "DC", 3, "DR", 1, "ML", 1, "MC", 2, "MR", 1, "ST", 1));
        FORMATIONS.put("3511", Map.of("GK", 1, "DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 3, "MR", 1, "ST", 2));

        // Substitution formats (1 per position that's used in the formation)
        Map<String, Integer> defaultSubs = Map.of("DL", 1, "DC", 1, "DR", 1, "ML", 1, "MC", 1, "MR", 1, "ST", 1);
        Map<String, Integer> narrowSubs = Map.of("DL", 1, "DC", 1, "DR", 1, "MC", 2, "ST", 1);

        for (String formation : FORMATIONS.keySet()) {
            Map<String, Integer> formMap = FORMATIONS.get(formation);
            boolean hasML = formMap.getOrDefault("ML", 0) > 0;
            boolean hasMR = formMap.getOrDefault("MR", 0) > 0;

            if (!hasML && !hasMR) {
                SUBSTITUTION_FORMATS.put(formation, narrowSubs);
            } else {
                SUBSTITUTION_FORMATS.put(formation, defaultSubs);
            }
        }
    }

    public Map<String, Integer> getRoomInTeamByTactic(String tactic) {
        return FORMATIONS.getOrDefault(tactic, FORMATIONS.get("442"));
    }

    public Map<String, Integer> getSubstitutionsInTeamByTactic(String tactic) {
        return SUBSTITUTION_FORMATS.getOrDefault(tactic, SUBSTITUTION_FORMATS.get("442"));
    }

    public Integer getValueForTacticDisplay(String position) {
        List<String> positions = List.of("GK", "DL", "DC", "DR", "ML", "MC", "MR", "ST");
        return positions.indexOf(position);
    }

    public List<String> getAllExistingTactics() {
        return new ArrayList<>(FORMATIONS.keySet());
    }

    /**
     * Builds a tactical "kit" for an AI manager:
     *  - count of known tactics scales with rating (weak coach ~2, top coach ~5)
     *  - the preferred tactic is one of those known
     *  - each manager gets a DIFFERENT mix, so no two coaches in the same league
     *    should default to 442 anymore.
     *
     * Returns {preferred, "tac1,tac2,tac3,..."} so callers can write both fields
     * on the Human entity in one shot.
     */
    public String[] buildManagerTacticKit(int rating, Random random) {
        List<String> all = getAllExistingTactics();
        int knownCount;
        if (rating < 40) knownCount = 2;
        else if (rating < 60) knownCount = 3;
        else if (rating < 80) knownCount = 4;
        else if (rating < 95) knownCount = 5;
        else knownCount = 6;
        knownCount = Math.min(knownCount, all.size());

        List<String> shuffled = new ArrayList<>(all);
        Collections.shuffle(shuffled, random);
        List<String> known = shuffled.subList(0, knownCount);

        // Preferred is just a random pick from the known set so two managers
        // with the same kit still differ in default formation.
        String preferred = known.get(random.nextInt(known.size()));

        return new String[]{ preferred, String.join(",", known) };
    }

    /**
     * Maps AMC/AML/AMR/DM grid positions to their base position equivalent.
     * Used for position matching in power calculations and role/instruction lookups.
     */
    public static String getBasePosition(String position) {
        if (position == null) return null;
        return switch (position) {
            case "AMC" -> "MC";
            case "AML" -> "ML";
            case "AMR" -> "MR";
            default -> position;
        };
    }

    // Maps frontend formation display names to their grid position indices (0-29 pitch cells)
    private static final Map<String, int[]> FORMATION_GRID_INDICES = new LinkedHashMap<>();

    static {
        FORMATION_GRID_INDICES.put("4-4-2",        new int[]{1, 3, 5, 9, 11, 13, 20, 21, 23, 24, 27});
        FORMATION_GRID_INDICES.put("4-3-3 DM",     new int[]{2, 5, 9, 11, 13, 17, 20, 21, 23, 24, 27});
        FORMATION_GRID_INDICES.put("4-2-3-1 Wide",  new int[]{2, 5, 7, 9, 16, 18, 20, 21, 23, 24, 27});
        FORMATION_GRID_INDICES.put("5-2-1-2 WB",   new int[]{1, 3, 7, 11, 13, 15, 19, 21, 22, 23, 27});
        FORMATION_GRID_INDICES.put("4-2-4",        new int[]{1, 3, 5, 9, 11, 13, 20, 21, 23, 24, 27});
    }

    public int[] getFormationGridIndices(String formationName) {
        return FORMATION_GRID_INDICES.getOrDefault(formationName, FORMATION_GRID_INDICES.get("4-4-2"));
    }

    public String getPositionFromIndex(int index) {

        if (index >= 30) return "Substitute";

        // --- Rândul 1 (0-4): Atacanți ---
        if (index <= 4) return "ST";

        // --- Rândul 2 (5-9): Mijlocași Ofensivi ---
        if (index == 5) return "AML"; // Sau ML, depinde cum ai in DB
        if (index == 9) return "AMR"; // Sau MR
        if (index <= 8) return "AMC"; // Sau MC

        // --- Rândul 3 (10-14): Mijlocași Centrali ---
        if (index == 10) return "ML";
        if (index == 14) return "MR";
        if (index <= 13) return "MC";

        // --- Rândul 4 (15-19): Mijlocași Defensivi / Wing Backs ---
        // AICI ERA PROBLEMA: Lipseau 16, 17, 18
        if (index == 15) return "DL"; // WB stanga
        if (index == 19) return "DR"; // WB dreapta
        if (index <= 18) return "MC";

        // --- Rândul 5 (20-24): Fundași ---
        if (index == 20) return "DL";
        if (index == 24) return "DR";
        if (index <= 23) return "DC";

        // --- Rândul 6 (25-29): Portar ---
        if (index == 27) return "GK";

        return "Unknown";
    }

    /**
     * Minimum squad-position counts that AI transfer planning treats as
     * essential coverage — used by {@code CompositeTransferStrategy.playersToSell}
     * to refuse selling below these thresholds.
     */
    public HashMap<String, Integer> getMinimumPositionNeeded() {
        HashMap<String, Integer> minimumPositionNeeded = new HashMap<>();
        minimumPositionNeeded.put("GK", 1);
        minimumPositionNeeded.put("DL", 1);
        minimumPositionNeeded.put("DC", 2);
        minimumPositionNeeded.put("DR", 1);
        minimumPositionNeeded.put("MC", 2);
        minimumPositionNeeded.put("ML", 1);
        minimumPositionNeeded.put("MR", 1);
        minimumPositionNeeded.put("ST", 2);
        return minimumPositionNeeded;
    }

    /**
     * Per-position upper caps that AI transfer planning treats as roster
     * saturation — used by {@code CompositeTransferStrategy.playersToBuy}
     * to skip buying when the position is already maxed out.
     */
    public HashMap<String, Integer> getMaximumPositionAllowed() {
        HashMap<String, Integer> maximumPositionAllowed = new HashMap<>();
        maximumPositionAllowed.put("GK", 3);
        maximumPositionAllowed.put("DL", 3);
        maximumPositionAllowed.put("DC", 5);
        maximumPositionAllowed.put("DR", 3);
        maximumPositionAllowed.put("MC", 5);
        maximumPositionAllowed.put("ML", 3);
        maximumPositionAllowed.put("MR", 3);
        maximumPositionAllowed.put("ST", 5);
        return maximumPositionAllowed;
    }
}
