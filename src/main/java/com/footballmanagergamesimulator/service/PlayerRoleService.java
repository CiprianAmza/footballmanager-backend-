package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Defines player roles per position, their attribute weights, and suitability calculations.
 * Based on Football Manager's role system:
 * - Each position has multiple available roles (e.g., ST can be Advanced Forward, Poacher, Target Man...)
 * - Each role has a duty: Attack, Support, or Defend
 * - A player's suitability for a role depends on how their attributes match the role's key attributes
 * - Role suitability produces an "effective rating" used in match simulation
 */
@Service
public class PlayerRoleService {

    @Autowired
    private MatchEngineConfig engineConfig;

    /**
     * Get available roles for a given position.
     * Returns list of role definitions with name, available duties, and key attributes.
     */
    public List<RoleDef> getRolesForPosition(String position) {
        if (position == null) return List.of();
        return ROLES_BY_POSITION.getOrDefault(position, List.of());
    }

    /**
     * Get all role names available for a position.
     */
    public List<String> getRoleNames(String position) {
        return getRolesForPosition(position).stream().map(r -> r.name).toList();
    }

    /**
     * Compute how suitable a player is for a specific role (0-100 scale).
     * Higher = better fit. Based on weighted attribute matching.
     */
    public double computeRoleSuitability(PlayerSkills skills, String roleName) {
        RoleDef role = findRole(skills.getPosition(), roleName);
        if (role == null) {
            // Unknown role: fall back to position-based rating
            return PlayerSkillsService.computeOverallRating(skills);
        }

        double weighted = 0;
        for (Map.Entry<String, Double> entry : role.keyAttributes.entrySet()) {
            String attr = entry.getKey();
            double weight = entry.getValue();
            var getter = PlayerSkillsService.GETTER_MAP.get(attr);
            if (getter != null) {
                weighted += getter.apply(skills) * weight;
            }
        }

        // Scale from 1-20 weighted average to 0-100 (scale factor is config-tunable).
        return Math.max(1, Math.min(100, weighted * engineConfig.getRoleWeights().getSuitabilityScale()));
    }

    /**
     * Compute the effective match rating for a player in a specific role, blending the
     * generic overall rating with role suitability (config-tunable blend weights).
     */
    public double computeEffectiveRating(PlayerSkills skills, String roleName) {
        return computeEffectiveRating(skills, roleName, PlayerSkillsService.computeOverallRating(skills));
    }

    /**
     * Same blend as {@link #computeEffectiveRating(PlayerSkills, String)} but with a
     * caller-supplied base value (e.g. the position-weighted match value from
     * {@code PlayerValueService}) standing in for the generic overall rating. The blend
     * weights live in {@code match.engine.role-weights} so designers can tune how much role
     * suitability matters relative to raw attribute value.
     */
    public double computeEffectiveRating(PlayerSkills skills, String roleName, double baseValue) {
        if (roleName == null || roleName.isEmpty()) {
            return baseValue;
        }
        double roleSuitability = computeRoleSuitability(skills, roleName);
        MatchEngineConfig.RoleWeights rw = engineConfig.getRoleWeights();
        return baseValue * rw.getOverallBlend() + roleSuitability * rw.getRoleBlend();
    }

    /**
     * Get a natural language description of a role.
     */
    public String getRoleDescription(String position, String roleName) {
        RoleDef role = findRole(position, roleName);
        return role != null ? role.description : "";
    }

    /**
     * Get the best role for a player based on their skills.
     * Returns the role name with the highest suitability score.
     */
    public String getBestRole(PlayerSkills skills) {
        List<RoleDef> roles = getRolesForPosition(skills.getPosition());
        if (roles.isEmpty()) return null;

        String bestRole = roles.get(0).name;
        double bestScore = 0;

        for (RoleDef role : roles) {
            double score = computeRoleSuitability(skills, role.name);
            if (score > bestScore) {
                bestScore = score;
                bestRole = role.name;
            }
        }
        return bestRole;
    }

    private RoleDef findRole(String position, String roleName) {
        if (position == null || roleName == null) return null;
        return getRolesForPosition(position).stream()
                .filter(r -> r.name.equals(roleName))
                .findFirst()
                .orElse(null);
    }

    // ==================== ROLE DEFINITIONS ====================

    public static class RoleDef {
        public final String name;
        public final String description;
        public final List<String> duties; // "Attack", "Support", "Defend"
        public final Map<String, Double> keyAttributes; // attribute name -> weight (sum to 1.0)

        public RoleDef(String name, String description, List<String> duties, Map<String, Double> keyAttributes) {
            this.name = name;
            this.description = description;
            this.duties = duties;
            this.keyAttributes = keyAttributes;
        }
    }

    private static final Map<String, List<RoleDef>> ROLES_BY_POSITION = new LinkedHashMap<>();

    static {
        // === GOALKEEPER ===
        ROLES_BY_POSITION.put("GK", List.of(
                new RoleDef("Goalkeeper", "Traditional shot-stopper",
                        List.of("Defend"),
                        Map.ofEntries(
                                Map.entry("Reflexes", 0.20), Map.entry("Handling", 0.18),
                                Map.entry("Positioning", 0.14), Map.entry("One On Ones", 0.10),
                                Map.entry("Command Of Area", 0.10), Map.entry("Concentration", 0.08),
                                Map.entry("Anticipation", 0.06), Map.entry("Kicking", 0.06),
                                Map.entry("Agility", 0.04), Map.entry("Composure", 0.04)
                        )),
                new RoleDef("Sweeper Keeper", "Comes off line, plays with feet",
                        List.of("Defend", "Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Reflexes", 0.14), Map.entry("Handling", 0.10),
                                Map.entry("One On Ones", 0.12), Map.entry("Rushing Out", 0.0), // mapped to Command Of Area
                                Map.entry("Command Of Area", 0.12), Map.entry("Kicking", 0.10),
                                Map.entry("First Touch", 0.08), Map.entry("Passing", 0.08),
                                Map.entry("Anticipation", 0.08), Map.entry("Composure", 0.08),
                                Map.entry("Positioning", 0.05), Map.entry("Agility", 0.05)
                        ))
        ));

        // === CENTRE-BACK ===
        ROLES_BY_POSITION.put("DC", List.of(
                new RoleDef("Central Defender", "Standard centre-back, focuses on defending",
                        List.of("Defend", "Support"),
                        Map.ofEntries(
                                Map.entry("Tackling", 0.15), Map.entry("Marking", 0.14),
                                Map.entry("Positioning", 0.13), Map.entry("Heading", 0.10),
                                Map.entry("Strength", 0.08), Map.entry("Concentration", 0.08),
                                Map.entry("Anticipation", 0.08), Map.entry("Bravery", 0.06),
                                Map.entry("Jumping Reach", 0.06), Map.entry("Composure", 0.04),
                                Map.entry("Pace", 0.04), Map.entry("Decisions", 0.04)
                        )),
                new RoleDef("Ball-Playing Defender", "Defender who starts attacks with passing",
                        List.of("Defend", "Support"),
                        Map.ofEntries(
                                Map.entry("Tackling", 0.10), Map.entry("Marking", 0.10),
                                Map.entry("Positioning", 0.10), Map.entry("Passing", 0.12),
                                Map.entry("First Touch", 0.08), Map.entry("Composure", 0.08),
                                Map.entry("Vision", 0.06), Map.entry("Technique", 0.06),
                                Map.entry("Concentration", 0.06), Map.entry("Anticipation", 0.06),
                                Map.entry("Heading", 0.06), Map.entry("Decisions", 0.06),
                                Map.entry("Strength", 0.06)
                        )),
                new RoleDef("No-Nonsense Defender", "Physical, aggressive, clears everything",
                        List.of("Defend"),
                        Map.ofEntries(
                                Map.entry("Tackling", 0.14), Map.entry("Marking", 0.14),
                                Map.entry("Heading", 0.12), Map.entry("Strength", 0.12),
                                Map.entry("Bravery", 0.10), Map.entry("Positioning", 0.10),
                                Map.entry("Jumping Reach", 0.08), Map.entry("Aggression", 0.08),
                                Map.entry("Concentration", 0.06), Map.entry("Anticipation", 0.06)
                        ))
        ));

        // === FULL-BACKS ===
        List<RoleDef> fbRoles = List.of(
                new RoleDef("Full-Back", "Defensive-minded full-back",
                        List.of("Defend", "Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Tackling", 0.12), Map.entry("Marking", 0.10),
                                Map.entry("Positioning", 0.10), Map.entry("Pace", 0.10),
                                Map.entry("Stamina", 0.08), Map.entry("Work Rate", 0.08),
                                Map.entry("Crossing", 0.06), Map.entry("Concentration", 0.06),
                                Map.entry("Anticipation", 0.06), Map.entry("Teamwork", 0.06),
                                Map.entry("Acceleration", 0.06), Map.entry("Decisions", 0.06),
                                Map.entry("Strength", 0.06)
                        )),
                new RoleDef("Wing-Back", "Attacking full-back who provides width",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Crossing", 0.14), Map.entry("Pace", 0.12),
                                Map.entry("Stamina", 0.10), Map.entry("Dribbling", 0.08),
                                Map.entry("Acceleration", 0.08), Map.entry("Work Rate", 0.08),
                                Map.entry("Tackling", 0.06), Map.entry("Passing", 0.06),
                                Map.entry("Technique", 0.06), Map.entry("Off The Ball", 0.06),
                                Map.entry("Teamwork", 0.06), Map.entry("Agility", 0.05),
                                Map.entry("Decisions", 0.05)
                        )),
                new RoleDef("Inverted Wing-Back", "Cuts inside to play through the middle",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Passing", 0.12), Map.entry("Dribbling", 0.10),
                                Map.entry("First Touch", 0.08), Map.entry("Technique", 0.08),
                                Map.entry("Vision", 0.08), Map.entry("Decisions", 0.08),
                                Map.entry("Composure", 0.08), Map.entry("Pace", 0.06),
                                Map.entry("Tackling", 0.06), Map.entry("Acceleration", 0.06),
                                Map.entry("Stamina", 0.06), Map.entry("Work Rate", 0.06),
                                Map.entry("Off The Ball", 0.08)
                        ))
        );
        ROLES_BY_POSITION.put("DL", fbRoles);
        ROLES_BY_POSITION.put("DR", fbRoles);

        // === CENTRAL MIDFIELD ===
        ROLES_BY_POSITION.put("MC", List.of(
                new RoleDef("Central Midfielder", "Balanced all-round midfielder",
                        List.of("Defend", "Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Passing", 0.14), Map.entry("Tackling", 0.08),
                                Map.entry("Decisions", 0.08), Map.entry("Teamwork", 0.08),
                                Map.entry("First Touch", 0.07), Map.entry("Technique", 0.07),
                                Map.entry("Stamina", 0.07), Map.entry("Work Rate", 0.07),
                                Map.entry("Positioning", 0.06), Map.entry("Vision", 0.06),
                                Map.entry("Concentration", 0.06), Map.entry("Composure", 0.06),
                                Map.entry("Anticipation", 0.05), Map.entry("Dribbling", 0.05)
                        )),
                new RoleDef("Deep-Lying Playmaker", "Dictates play from deep with passing",
                        List.of("Defend", "Support"),
                        Map.ofEntries(
                                Map.entry("Passing", 0.18), Map.entry("Vision", 0.14),
                                Map.entry("First Touch", 0.10), Map.entry("Technique", 0.10),
                                Map.entry("Composure", 0.08), Map.entry("Decisions", 0.08),
                                Map.entry("Teamwork", 0.06), Map.entry("Anticipation", 0.06),
                                Map.entry("Flair", 0.05), Map.entry("Concentration", 0.05),
                                Map.entry("Positioning", 0.05), Map.entry("Balance", 0.05)
                        )),
                new RoleDef("Ball-Winning Midfielder", "Aggressive midfielder who wins the ball",
                        List.of("Defend", "Support"),
                        Map.ofEntries(
                                Map.entry("Tackling", 0.16), Map.entry("Work Rate", 0.12),
                                Map.entry("Stamina", 0.10), Map.entry("Aggression", 0.10),
                                Map.entry("Anticipation", 0.08), Map.entry("Positioning", 0.08),
                                Map.entry("Teamwork", 0.06), Map.entry("Strength", 0.06),
                                Map.entry("Bravery", 0.06), Map.entry("Concentration", 0.06),
                                Map.entry("Marking", 0.06), Map.entry("Decisions", 0.06)
                        )),
                new RoleDef("Box-to-Box Midfielder", "Covers the whole pitch end to end",
                        List.of("Support"),
                        Map.ofEntries(
                                Map.entry("Stamina", 0.12), Map.entry("Work Rate", 0.10),
                                Map.entry("Passing", 0.08), Map.entry("Tackling", 0.08),
                                Map.entry("Finishing", 0.06), Map.entry("Long Shots", 0.06),
                                Map.entry("Off The Ball", 0.06), Map.entry("Teamwork", 0.06),
                                Map.entry("Pace", 0.06), Map.entry("Decisions", 0.06),
                                Map.entry("Dribbling", 0.06), Map.entry("First Touch", 0.06),
                                Map.entry("Anticipation", 0.06), Map.entry("Strength", 0.04),
                                Map.entry("Technique", 0.04)
                        )),
                new RoleDef("Advanced Playmaker", "Creative midfielder in the final third",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Vision", 0.14), Map.entry("Passing", 0.14),
                                Map.entry("First Touch", 0.10), Map.entry("Technique", 0.10),
                                Map.entry("Flair", 0.08), Map.entry("Composure", 0.08),
                                Map.entry("Decisions", 0.08), Map.entry("Dribbling", 0.08),
                                Map.entry("Off The Ball", 0.06), Map.entry("Anticipation", 0.06),
                                Map.entry("Agility", 0.04), Map.entry("Balance", 0.04)
                        )),
                new RoleDef("Mezzala", "Half-space midfielder who moves into wide areas",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Dribbling", 0.10), Map.entry("Passing", 0.10),
                                Map.entry("Technique", 0.08), Map.entry("Off The Ball", 0.08),
                                Map.entry("Vision", 0.08), Map.entry("Decisions", 0.08),
                                Map.entry("Acceleration", 0.06), Map.entry("Pace", 0.06),
                                Map.entry("First Touch", 0.06), Map.entry("Long Shots", 0.06),
                                Map.entry("Finishing", 0.06), Map.entry("Stamina", 0.06),
                                Map.entry("Work Rate", 0.06), Map.entry("Flair", 0.06)
                        ))
        ));

        // === WIDE MIDFIELD / WINGER ===
        List<RoleDef> wideRoles = List.of(
                new RoleDef("Winger", "Wide attacker who hugs the touchline",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Crossing", 0.14), Map.entry("Dribbling", 0.12),
                                Map.entry("Pace", 0.10), Map.entry("Acceleration", 0.08),
                                Map.entry("Technique", 0.08), Map.entry("Agility", 0.06),
                                Map.entry("Flair", 0.06), Map.entry("Off The Ball", 0.06),
                                Map.entry("First Touch", 0.06), Map.entry("Passing", 0.06),
                                Map.entry("Stamina", 0.06), Map.entry("Work Rate", 0.06),
                                Map.entry("Decisions", 0.06)
                        )),
                new RoleDef("Inside Forward", "Cuts inside to score or create",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Finishing", 0.14), Map.entry("Dribbling", 0.12),
                                Map.entry("Acceleration", 0.08), Map.entry("Off The Ball", 0.08),
                                Map.entry("Composure", 0.08), Map.entry("First Touch", 0.06),
                                Map.entry("Technique", 0.06), Map.entry("Pace", 0.06),
                                Map.entry("Passing", 0.06), Map.entry("Long Shots", 0.06),
                                Map.entry("Anticipation", 0.06), Map.entry("Decisions", 0.06),
                                Map.entry("Agility", 0.04), Map.entry("Flair", 0.04)
                        )),
                new RoleDef("Wide Midfielder", "Balanced wide player",
                        List.of("Defend", "Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Crossing", 0.10), Map.entry("Work Rate", 0.10),
                                Map.entry("Stamina", 0.10), Map.entry("Passing", 0.08),
                                Map.entry("Tackling", 0.08), Map.entry("Teamwork", 0.08),
                                Map.entry("Pace", 0.06), Map.entry("Dribbling", 0.06),
                                Map.entry("Positioning", 0.06), Map.entry("Decisions", 0.06),
                                Map.entry("Technique", 0.06), Map.entry("Concentration", 0.06),
                                Map.entry("Anticipation", 0.05), Map.entry("First Touch", 0.05)
                        )),
                new RoleDef("Inverted Winger", "Wide player who cuts inside with the ball",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Dribbling", 0.14), Map.entry("Passing", 0.10),
                                Map.entry("Vision", 0.08), Map.entry("Technique", 0.08),
                                Map.entry("First Touch", 0.08), Map.entry("Acceleration", 0.08),
                                Map.entry("Agility", 0.06), Map.entry("Pace", 0.06),
                                Map.entry("Long Shots", 0.06), Map.entry("Composure", 0.06),
                                Map.entry("Decisions", 0.06), Map.entry("Flair", 0.06),
                                Map.entry("Off The Ball", 0.04), Map.entry("Finishing", 0.04)
                        ))
        );
        ROLES_BY_POSITION.put("ML", wideRoles);
        ROLES_BY_POSITION.put("MR", wideRoles);

        // === STRIKER ===
        ROLES_BY_POSITION.put("ST", List.of(
                new RoleDef("Advanced Forward", "All-round striker who leads the line",
                        List.of("Attack"),
                        Map.ofEntries(
                                Map.entry("Finishing", 0.14), Map.entry("Off The Ball", 0.10),
                                Map.entry("Composure", 0.08), Map.entry("Dribbling", 0.08),
                                Map.entry("First Touch", 0.08), Map.entry("Technique", 0.06),
                                Map.entry("Anticipation", 0.06), Map.entry("Pace", 0.06),
                                Map.entry("Acceleration", 0.06), Map.entry("Decisions", 0.06),
                                Map.entry("Heading", 0.06), Map.entry("Strength", 0.04),
                                Map.entry("Agility", 0.04), Map.entry("Balance", 0.04),
                                Map.entry("Flair", 0.04)
                        )),
                new RoleDef("Poacher", "Goal-focused striker, always in the box",
                        List.of("Attack"),
                        Map.ofEntries(
                                Map.entry("Finishing", 0.20), Map.entry("Off The Ball", 0.14),
                                Map.entry("Anticipation", 0.12), Map.entry("Composure", 0.10),
                                Map.entry("Heading", 0.08), Map.entry("First Touch", 0.06),
                                Map.entry("Technique", 0.06), Map.entry("Concentration", 0.06),
                                Map.entry("Pace", 0.06), Map.entry("Acceleration", 0.06),
                                Map.entry("Decisions", 0.06)
                        )),
                new RoleDef("Target Man", "Holds up the ball, wins headers",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Heading", 0.14), Map.entry("Strength", 0.14),
                                Map.entry("Jumping Reach", 0.10), Map.entry("First Touch", 0.08),
                                Map.entry("Finishing", 0.08), Map.entry("Balance", 0.06),
                                Map.entry("Bravery", 0.06), Map.entry("Composure", 0.06),
                                Map.entry("Off The Ball", 0.06), Map.entry("Passing", 0.06),
                                Map.entry("Teamwork", 0.06), Map.entry("Anticipation", 0.05),
                                Map.entry("Decisions", 0.05)
                        )),
                new RoleDef("Deep-Lying Forward", "Drops deep to link midfield and attack",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("First Touch", 0.10), Map.entry("Passing", 0.10),
                                Map.entry("Technique", 0.10), Map.entry("Vision", 0.08),
                                Map.entry("Composure", 0.08), Map.entry("Dribbling", 0.08),
                                Map.entry("Flair", 0.06), Map.entry("Off The Ball", 0.06),
                                Map.entry("Finishing", 0.06), Map.entry("Decisions", 0.06),
                                Map.entry("Teamwork", 0.06), Map.entry("Strength", 0.06),
                                Map.entry("Balance", 0.05), Map.entry("Anticipation", 0.05)
                        )),
                new RoleDef("Pressing Forward", "High pressing, harasses defenders",
                        List.of("Defend", "Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Work Rate", 0.14), Map.entry("Aggression", 0.10),
                                Map.entry("Stamina", 0.10), Map.entry("Finishing", 0.08),
                                Map.entry("Anticipation", 0.08), Map.entry("Bravery", 0.06),
                                Map.entry("Off The Ball", 0.06), Map.entry("Teamwork", 0.06),
                                Map.entry("Pace", 0.06), Map.entry("Acceleration", 0.06),
                                Map.entry("Decisions", 0.06), Map.entry("Composure", 0.06),
                                Map.entry("Strength", 0.04), Map.entry("First Touch", 0.04)
                        )),
                new RoleDef("Complete Forward", "Can do everything — the ultimate striker",
                        List.of("Support", "Attack"),
                        Map.ofEntries(
                                Map.entry("Finishing", 0.10), Map.entry("Heading", 0.06),
                                Map.entry("Dribbling", 0.08), Map.entry("First Touch", 0.08),
                                Map.entry("Off The Ball", 0.08), Map.entry("Passing", 0.06),
                                Map.entry("Technique", 0.06), Map.entry("Composure", 0.06),
                                Map.entry("Strength", 0.06), Map.entry("Pace", 0.06),
                                Map.entry("Anticipation", 0.06), Map.entry("Decisions", 0.06),
                                Map.entry("Vision", 0.04), Map.entry("Work Rate", 0.04),
                                Map.entry("Flair", 0.04), Map.entry("Acceleration", 0.04),
                                Map.entry("Balance", 0.02)
                        ))
        ));
    }
}
