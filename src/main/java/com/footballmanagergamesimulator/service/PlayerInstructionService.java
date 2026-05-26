package com.footballmanagergamesimulator.service;

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
    public static double computeInstructionMultiplier(List<String> instructions, String position, String matchContext) {
        if (instructions == null || instructions.isEmpty()) return 1.0;

        double bonus = 0;

        for (String instruction : instructions) {
            switch (instruction) {
                // Defensive instructions
                case "Mark Tighter" -> {
                    if (Set.of("DC", "DL", "DR").contains(position)) bonus += 0.01;
                    else bonus -= 0.005; // risky for midfielders
                }
                case "Close Down More" -> {
                    if ("ST".equals(position)) bonus += 0.01; // pressing forward
                    else if (Set.of("DC").contains(position)) bonus -= 0.005; // risky for CBs
                    else bonus += 0.005;
                }
                case "Close Down Less" -> {
                    if (Set.of("DC").contains(position)) bonus += 0.005;
                    else bonus -= 0.005;
                }
                case "Tackle Harder" -> bonus += 0.005; // riskier but wins ball more
                case "Stay On Feet" -> bonus += 0.003;
                case "Ease Off Tackles" -> bonus -= 0.003;

                // Attacking instructions
                case "Get Further Forward" -> {
                    if (Set.of("DL", "DR", "ML", "MR").contains(position)) bonus += 0.01;
                    else if ("MC".equals(position)) bonus += 0.005;
                }
                case "Hold Position" -> {
                    if (Set.of("DC").contains(position)) bonus += 0.005;
                }
                case "Shoot More Often" -> {
                    if ("ST".equals(position)) bonus += 0.01;
                    else bonus += 0.005;
                }
                case "Shoot Less Often" -> bonus += 0.003; // better teamplay
                case "Dribble More" -> {
                    if (Set.of("ML", "MR").contains(position)) bonus += 0.01;
                    else bonus += 0.003;
                }
                case "Dribble Less" -> bonus += 0.003;

                // Movement
                case "Roam From Position" -> {
                    if (Set.of("MC", "ST").contains(position)) bonus += 0.005;
                }
                case "Stay Wider" -> {
                    if (Set.of("ML", "MR").contains(position)) bonus += 0.008;
                }
                case "Sit Narrower" -> {
                    if (Set.of("ML", "MR").contains(position)) bonus += 0.005;
                }
                case "Move Into Channels" -> bonus += 0.005;
                case "Drop Deeper" -> {
                    if ("ST".equals(position)) bonus += 0.005;
                }

                // Passing
                case "Pass It Shorter" -> bonus += 0.003;
                case "Try More Direct Passes" -> bonus += 0.003;
                case "Cross From Byline" -> {
                    if (Set.of("ML", "MR", "DL", "DR").contains(position)) bonus += 0.008;
                }
                case "Cross From Deep" -> {
                    if (Set.of("ML", "MR", "DL", "DR").contains(position)) bonus += 0.005;
                }
                case "Play Through Balls" -> bonus += 0.005;
            }
        }

        // Check for conflicting instructions (penalty)
        Set<String> instrSet = new HashSet<>(instructions);
        if (instrSet.contains("Close Down More") && instrSet.contains("Close Down Less")) bonus -= 0.02;
        if (instrSet.contains("Shoot More Often") && instrSet.contains("Shoot Less Often")) bonus -= 0.02;
        if (instrSet.contains("Dribble More") && instrSet.contains("Dribble Less")) bonus -= 0.02;
        if (instrSet.contains("Sit Narrower") && instrSet.contains("Stay Wider")) bonus -= 0.02;
        if (instrSet.contains("Cross From Byline") && instrSet.contains("Cross From Deep")) bonus -= 0.02;
        if (instrSet.contains("Tackle Harder") && instrSet.contains("Ease Off Tackles")) bonus -= 0.02;
        if (instrSet.contains("Get Further Forward") && instrSet.contains("Hold Position")) bonus -= 0.02;

        return Math.max(0.92, Math.min(1.08, 1.0 + bonus));
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
