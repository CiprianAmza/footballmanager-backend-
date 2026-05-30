package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Defines individual player instructions available per position.
 * Each instruction modifies how the player behaves tactically in a match,
 * affecting team power calculation and match simulation.
 *
 * Instructions are grouped into categories:
 * - Defensive: marking, closing down, tackling style
 * - Attacking: forward runs, shooting, crossing
 * - Movement: roaming, holding position, width
 * - Passing: pass length, distribution
 */
@Service
public class PlayerInstructionService {

    @Autowired
    MatchEngineConfig engineConfig;

    // All available instructions with their categories, applicable positions, and descriptions
    private static final List<InstructionDef> ALL_INSTRUCTIONS = List.of(
            // === DEFENSIVE ===
            new InstructionDef("Mark Tighter", "Defensive",
                    "Player marks opponents more closely, reducing space but risking being turned",
                    Set.of("DC", "DL", "DR", "MC", "ML", "MR")),
            new InstructionDef("Close Down More", "Defensive",
                    "Player presses opponents more aggressively when they receive the ball",
                    Set.of("DC", "DL", "DR", "MC", "ML", "MR", "ST")),
            new InstructionDef("Close Down Less", "Defensive",
                    "Player holds position instead of pressing, maintaining defensive shape",
                    Set.of("DC", "DL", "DR", "MC", "ML", "MR", "ST")),
            new InstructionDef("Tackle Harder", "Defensive",
                    "Player commits to harder tackles, winning ball but risking fouls/cards",
                    Set.of("DC", "DL", "DR", "MC")),
            new InstructionDef("Stay On Feet", "Defensive",
                    "Player avoids sliding tackles, jockeying instead for safer defending",
                    Set.of("DC", "DL", "DR", "MC")),
            new InstructionDef("Ease Off Tackles", "Defensive",
                    "Player tackles more carefully, reducing card risk but may lose duels",
                    Set.of("DC", "DL", "DR", "MC", "ML", "MR")),

            // === ATTACKING ===
            new InstructionDef("Get Further Forward", "Attacking",
                    "Player pushes higher up the pitch during attacks",
                    Set.of("DL", "DR", "MC", "ML", "MR")),
            new InstructionDef("Hold Position", "Attacking",
                    "Player stays in assigned position, doesn't make forward runs",
                    Set.of("DC", "DL", "DR", "MC")),
            new InstructionDef("Shoot More Often", "Attacking",
                    "Player takes more shots instead of looking for a pass",
                    Set.of("MC", "ML", "MR", "ST")),
            new InstructionDef("Shoot Less Often", "Attacking",
                    "Player looks to pass or cross rather than shoot",
                    Set.of("MC", "ML", "MR", "ST")),
            new InstructionDef("Dribble More", "Attacking",
                    "Player attempts to beat opponents with dribbling more often",
                    Set.of("ML", "MR", "ST", "MC")),
            new InstructionDef("Dribble Less", "Attacking",
                    "Player releases the ball quicker, playing simpler",
                    Set.of("ML", "MR", "ST", "MC")),

            // === MOVEMENT ===
            new InstructionDef("Roam From Position", "Movement",
                    "Player has freedom to move away from assigned position to find space",
                    Set.of("MC", "ML", "MR", "ST")),
            new InstructionDef("Sit Narrower", "Movement",
                    "Player moves towards the center of the pitch",
                    Set.of("DL", "DR", "ML", "MR")),
            new InstructionDef("Stay Wider", "Movement",
                    "Player hugs the touchline, providing width in attack",
                    Set.of("DL", "DR", "ML", "MR")),
            new InstructionDef("Move Into Channels", "Movement",
                    "Player drifts into the half-spaces between defenders",
                    Set.of("ST", "MC", "ML", "MR")),
            new InstructionDef("Drop Deeper", "Movement",
                    "Forward drops into midfield to receive ball, linking play",
                    Set.of("ST")),

            // === PASSING ===
            new InstructionDef("Pass It Shorter", "Passing",
                    "Player plays shorter, safer passes to retain possession",
                    Set.of("DC", "DL", "DR", "MC", "ML", "MR", "ST")),
            new InstructionDef("Try More Direct Passes", "Passing",
                    "Player attempts more forward, penetrating passes",
                    Set.of("DC", "DL", "DR", "MC", "ML", "MR")),
            new InstructionDef("Cross From Byline", "Passing",
                    "Player runs to the byline before crossing rather than crossing early",
                    Set.of("DL", "DR", "ML", "MR")),
            new InstructionDef("Cross From Deep", "Passing",
                    "Player crosses the ball early from deeper positions",
                    Set.of("DL", "DR", "ML", "MR")),
            new InstructionDef("Play Through Balls", "Passing",
                    "Player looks for through-ball opportunities to runners",
                    Set.of("MC", "ML", "MR", "ST"))
    );

    /**
     * Get available instructions for a given position, grouped by category.
     */
    public static List<Map<String, Object>> getInstructionsForPosition(String position) {
        if (position == null) return List.of();

        return ALL_INSTRUCTIONS.stream()
                .filter(i -> i.positions.contains(position))
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", i.name);
                    m.put("category", i.category);
                    m.put("description", i.description);
                    return m;
                }).toList();
    }

    /**
     * Compute a power adjustment multiplier based on player instructions.
     * Good instruction choices boost power, bad ones reduce it.
     * Returns a multiplier centered on 1.0 (e.g., 1.02 = +2% boost).
     *
     * @param instructions the list of instruction names assigned to this player
     * @param position the player's position
     * @param matchContext "attacking" or "defending" phase
     * @return power multiplier (0.95 - 1.05 range)
     */
    public double computeInstructionMultiplier(List<String> instructions, String position, String matchContext) {
        if (instructions == null || instructions.isEmpty()) return 1.0;

        MatchEngineConfig.InstructionWeights cfg = engineConfig.getInstructionWeights();
        double bonus = 0;

        // Per-instruction bonuses are config-driven (base + per-position exceptions); see
        // MatchEngineConfig.InstructionWeights.DEFAULT_BONUSES for the shipped table.
        for (String instruction : instructions) {
            bonus += cfg.bonus(instruction, position);
        }

        // A global scale lets designers dial overall instruction impact up or down.
        bonus *= cfg.getBonusScale();

        // Conflicting instruction pairs each subtract the (config) conflict penalty. The pair
        // list itself is config-driven; see MatchEngineConfig.InstructionWeights.conflicts.
        double conflict = cfg.getConflictPenalty();
        Set<String> instrSet = new HashSet<>(instructions);
        for (MatchEngineConfig.InstructionWeights.ConflictPair pair : cfg.getConflicts()) {
            if (instrSet.contains(pair.getA()) && instrSet.contains(pair.getB())) bonus -= conflict;
        }

        return Math.max(cfg.getClampMin(), Math.min(cfg.getClampMax(), 1.0 + bonus));
    }

    public static class InstructionDef {
        public final String name;
        public final String category;
        public final String description;
        public final Set<String> positions;

        InstructionDef(String name, String category, String description, Set<String> positions) {
            this.name = name;
            this.category = category;
            this.description = description;
            this.positions = positions;
        }
    }
}
