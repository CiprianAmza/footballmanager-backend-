package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.PlayerAttributeMapping;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.service.PlayerCapabilityService;
import com.footballmanagergamesimulator.service.PlayerRoleService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Spring boundary that turns already-loaded runtime values into canonical Phase 7 input. */
@Service
public final class CanonicalRuntimeInputFactory {
    private static final Comparator<RuntimeLineupSlot> SLOT_ORDER =
            Comparator.comparing(RuntimeLineupSlot::usedPosition)
                    .thenComparingInt(RuntimeLineupSlot::occurrence)
                    .thenComparingLong(slot -> slot.player().getId());

    private final PlayerCapabilityService capabilityService;
    private final PlayerRoleService roleService;

    public CanonicalRuntimeInputFactory(PlayerCapabilityService capabilityService,
                                        PlayerRoleService roleService) {
        this.capabilityService = Objects.requireNonNull(capabilityService, "capabilityService");
        this.roleService = Objects.requireNonNull(roleService, "roleService");
    }

    public CanonicalRuntimeTeamInput build(PersonalizedTactic tactic,
                                           Collection<RuntimeLineupSlot> slots) {
        Objects.requireNonNull(tactic, "tactic");
        Objects.requireNonNull(slots, "slots");
        List<RuntimeLineupSlot> orderedSlots = new ArrayList<>(slots);
        if (orderedSlots.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("slots cannot contain null values");
        }
        orderedSlots.sort(SLOT_ORDER);
        if (orderedSlots.size() != 11) {
            throw new IllegalArgumentException("slots must contain exactly 11 players");
        }
        Set<Long> ids = new java.util.HashSet<>();
        for (RuntimeLineupSlot slot : orderedSlots) {
            if (!ids.add(slot.player().getId())) {
                throw new IllegalArgumentException("duplicate player id: " + slot.player().getId());
            }
        }

        List<Long> orderedIds = orderedSlots.stream().map(slot -> slot.player().getId()).toList();
        Map<Long, PlayerCapabilitySnapshot> capabilities = capabilityService.loadAll(orderedIds);
        CanonicalAxes axes = canonicalAxes(tactic);
        List<CanonicalLineupPlayer> lineup = new ArrayList<>();
        Map<Long, TacticalContextInput> contexts = new LinkedHashMap<>();
        for (RuntimeLineupSlot slot : orderedSlots) {
            FormationData formation = slot.formationData();
            PlayerRole role = resolveRole(slot.usedPosition(), formation);
            Duty duty = resolveDuty(formation == null ? null : formation.getDuty());
            validateDuty(slot, role, duty);
            double suitability = role == null ? 50.0
                    : roleService.computeRoleSuitability(slot.skills(), slot.usedPosition().code(), role.displayName());
            Set<PlayerTrait> traits = slot.player().isStayForward()
                    ? EnumSet.of(PlayerTrait.REFUSES_DEFENSIVE_WORK) : EnumSet.noneOf(PlayerTrait.class);
            ForwardInstruction instruction = resolveInstruction(formation == null ? null : formation.getInstructions());
            PlayerCapabilitySnapshot capability = Objects.requireNonNull(
                    capabilities.get(slot.player().getId()),
                    "missing capability snapshot for player " + slot.player().getId());
            CanonicalLineupPlayer canonical = new CanonicalLineupPlayer(
                    slot.player().getId(), slot.usedPosition(), slot.occurrence(), role, duty,
                    PlayerAttributeMapping.rawAttributeMap(slot.skills()), slot.player().getFitness(),
                    slot.player().getMorale(), capability, suitability, traits, instruction);
            lineup.add(canonical);
            contexts.put(canonical.playerId(), tacticalContext(axes, formation));
        }
        return new CanonicalRuntimeTeamInput(axes.mentality(), lineup, contexts);
    }

    /**
     * Re-evaluates team-level tactic axes without reloading immutable player capabilities.
     * Player roles, duties and individual instructions stay attached to the same lineup. This is
     * the canonical, database-free hot path used by Tactics Advisor when comparing many tactics.
     */
    public CanonicalRuntimeTeamInput withTactic(CanonicalRuntimeTeamInput baseline,
                                                PersonalizedTactic tactic) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(tactic, "tactic");
        CanonicalAxes axes = canonicalAxes(tactic);
        Map<Long, TacticalContextInput> contexts = new LinkedHashMap<>();
        for (CanonicalLineupPlayer player : baseline.lineup()) {
            TacticalContextInput previous = baseline.tacticalContexts().get(player.playerId());
            contexts.put(player.playerId(), new TacticalContextInput(
                    axes.mentalityText(), axes.tempo(), axes.passingType(), axes.defensiveLine(),
                    axes.pressing(), axes.width(), previous.playerInstructions()));
        }
        return new CanonicalRuntimeTeamInput(axes.mentality(), baseline.lineup(), contexts);
    }

    private void validateDuty(RuntimeLineupSlot slot, PlayerRole role, Duty duty) {
        if (role != null && !roleService.isDutyAllowed(slot.usedPosition().code(), role.displayName(), duty.name())) {
            throw new IllegalArgumentException("duty " + duty.name() + " is not allowed for role "
                    + role.displayName() + " at " + slot.usedPosition().code());
        }
    }

    private static PlayerRole resolveRole(com.footballmanagergamesimulator.compartment.PlayerPosition position,
                                          FormationData formation) {
        if (formation == null || formation.getRole() == null || formation.getRole().isBlank()) return null;
        PlayerRole role = PlayerRole.fromDisplayName(formation.getRole().trim()).orElseThrow(
                () -> new IllegalArgumentException("unknown role: " + formation.getRole()));
        new PositionRoleKey(position, role);
        return role;
    }

    private static Duty resolveDuty(String raw) {
        if (raw == null || raw.isBlank()) return Duty.SUPPORT;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ATTACK" -> Duty.ATTACK;
            case "SUPPORT" -> Duty.SUPPORT;
            case "DEFEND" -> Duty.DEFEND;
            default -> throw new IllegalArgumentException("unknown duty: " + raw);
        };
    }

    private static ForwardInstruction resolveInstruction(List<String> instructions) {
        boolean stayForward = false;
        boolean trackBack = false;
        if (instructions != null) {
            for (String instruction : instructions) {
                if (instruction == null) continue;
                String normalized = instruction.trim().toLowerCase(Locale.ROOT);
                stayForward |= normalized.equals("stay forward");
                trackBack |= normalized.equals("track back");
            }
        }
        if (stayForward && trackBack) {
            throw new IllegalArgumentException("Stay Forward and Track Back cannot both be selected");
        }
        if (stayForward) return ForwardInstruction.STAY_FORWARD;
        if (trackBack) return ForwardInstruction.TRACK_BACK;
        return ForwardInstruction.DEFAULT;
    }

    private static TacticalContextInput tacticalContext(CanonicalAxes axes, FormationData formation) {
        return new TacticalContextInput(
                axes.mentalityText(), axes.tempo(), axes.passingType(), axes.defensiveLine(), axes.pressing(), axes.width(),
                formation == null || formation.getInstructions() == null
                        ? List.of() : List.copyOf(formation.getInstructions()));
    }

    private static CanonicalAxes canonicalAxes(PersonalizedTactic tactic) {
        String mentality = canonicalAxis("mentality", tactic.getMentality(), "Balanced",
                MatchEngineConfig.TacticalModel.MENTALITY_OPTIONS);
        return new CanonicalAxes(
                resolveMentality(mentality), mentality,
                canonicalAxis("tempo", tactic.getTempo(), "Standard", MatchEngineConfig.TacticalModel.TEMPO_OPTIONS),
                canonicalAxis("passingType", tactic.getPassingType(), "Normal", MatchEngineConfig.TacticalModel.PASSING_OPTIONS),
                canonicalAxis("defensiveLine", tactic.getDefensiveLine(), "Standard", MatchEngineConfig.TacticalModel.DEFENSIVE_LINE_OPTIONS),
                canonicalAxis("pressing", tactic.getPressing(), "Standard", MatchEngineConfig.TacticalModel.PRESSING_OPTIONS),
                canonicalAxis("width", tactic.getWidth(), "Balanced", MatchEngineConfig.TacticalModel.WIDTH_OPTIONS));
    }

    private static String canonicalAxis(String axis, String raw, String fallback, List<String> options) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim();
        return options.stream().filter(option -> option.equalsIgnoreCase(value)).findFirst().orElseThrow(
                () -> new IllegalArgumentException("unknown " + axis + " value: " + raw));
    }

    private static Mentality resolveMentality(String raw) {
        return switch (raw) {
            case "Very Attacking" -> Mentality.VERY_ATTACKING;
            case "Attacking" -> Mentality.ATTACKING;
            case "Balanced" -> Mentality.BALANCED;
            case "Defensive" -> Mentality.DEFENSIVE;
            case "Very Defensive" -> Mentality.VERY_DEFENSIVE;
            default -> throw new IllegalArgumentException("unknown mentality: " + raw);
        };
    }

    private record CanonicalAxes(Mentality mentality, String mentalityText, String tempo, String passingType,
                                 String defensiveLine, String pressing, String width) {
    }
}
