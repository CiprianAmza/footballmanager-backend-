package com.footballmanagergamesimulator.chairman.mandate;

import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.service.CoachPermissionService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** The single runtime policy point for Chairman formation and XI constraints. */
@Service
public class ChairmanTacticalMandateEnforcementService {
    private final ChairmanTacticalMandateRepository mandateRepository;
    private final HumanRepository humanRepository;
    private final TacticService tacticService;

    public ChairmanTacticalMandateEnforcementService(ChairmanTacticalMandateRepository mandateRepository,
                                                     HumanRepository humanRepository,
                                                     TacticService tacticService) {
        this.mandateRepository = mandateRepository;
        this.humanRepository = humanRepository;
        this.tacticService = tacticService;
    }

    public EffectiveChairmanMandate mandate(long teamId) {
        return mandateRepository.findByTeamId(teamId).map(value -> new EffectiveChairmanMandate(
                value.getRequiredFormation(), value.sortedSlots().stream()
                .map(slot -> new EffectiveChairmanMandate.Slot(slot.getPositionIndex(), slot.getRequiredPlayerId()))
                .toList())).orElseGet(EffectiveChairmanMandate::absent);
    }

    public String effectiveFormation(long teamId, String proposedFormation) {
        String required = mandate(teamId).requiredFormation();
        return required != null ? required : proposedFormation;
    }

    /**
     * Applies the current mandate and then non-conflicting legacy locks to a copied
     * formation. Runtime mode omits unavailable mandated players; edit mode reports
     * stale membership instead of silently accepting it.
     */
    public List<FormationData> enforceFormation(long teamId, String proposedFormation,
                                                List<FormationData> submitted,
                                                List<CoachPermissionService.LockedSlot> legacyLocks,
                                                Set<Long> unavailableIds, boolean runtime) {
        EffectiveChairmanMandate current = mandate(teamId);
        Set<Long> unavailable = unavailableIds == null ? Set.of() : Set.copyOf(unavailableIds);
        Set<Integer> validGrid = validGrid(current.requiredFormation() != null
                ? current.requiredFormation() : proposedFormation, current.requiredFormation() != null);
        List<EffectiveChairmanMandate.Slot> mandatory = eligibleSlots(teamId, current, unavailable, runtime);

        List<FormationData> result = new ArrayList<>();
        Set<Integer> occupiedPositions = new HashSet<>();
        Set<Long> occupiedPlayers = new HashSet<>();
        for (FormationData original : submitted == null ? List.<FormationData>of() : submitted) {
            if (original == null || original.getPlayerId() <= 0) {
                if (!runtime) throw invalid("MANAGER_XI_INVALID", "Formation contains an invalid player");
                continue;
            }
            if (runtime && unavailable.contains(original.getPlayerId())) continue;
            FormationData copy = copy(original);
            boolean starter = copy.getPositionIndex() < 30;
            if (starter && current.requiredFormation() != null && !validGrid.contains(copy.getPositionIndex())) continue;
            if (mandatory.stream().anyMatch(slot -> slot.positionIndex() == copy.getPositionIndex()
                    || slot.playerId() == copy.getPlayerId())) continue;
            if (!occupiedPositions.add(copy.getPositionIndex()) || !occupiedPlayers.add(copy.getPlayerId())) {
                if (current.requiredFormation() == null && current.lockedSlots().isEmpty()) {
                    // No Chairman mandate: preserve the legacy snapshot exactly.
                    result.add(copy);
                    continue;
                }
                if (!runtime) throw invalid("MANAGER_XI_INVALID", "Formation contains duplicate slots or players");
                continue;
            }
            result.add(copy);
        }

        for (CoachPermissionService.LockedSlot legacy : legacyLocks == null ? List.<CoachPermissionService.LockedSlot>of() : legacyLocks) {
            if (legacy == null || legacy.positionIndex() >= 30 && legacy.positionIndex() <= 36
                    && mandatory.stream().anyMatch(slot -> slot.playerId() == legacy.playerId())) continue;
            if (mandatory.stream().anyMatch(slot -> slot.positionIndex() == legacy.positionIndex()
                    || slot.playerId() == legacy.playerId())) continue;
            Human player = humanRepository.findById(legacy.playerId()).orElse(null);
            if (player == null || player.getTeamId() == null || player.getTeamId() != teamId
                    || player.getTypeId() != TypeNames.PLAYER_TYPE || player.isRetired()
                    || unavailable.contains(player.getId())) continue;
            if (!occupiedPositions.add(legacy.positionIndex()) || !occupiedPlayers.add(legacy.playerId())) continue;
            FormationData forced = new FormationData();
            forced.setPositionIndex(legacy.positionIndex());
            forced.setPlayerId(legacy.playerId());
            result.add(forced);
        }

        for (EffectiveChairmanMandate.Slot slot : mandatory) {
            if (!occupiedPositions.add(slot.positionIndex()) || !occupiedPlayers.add(slot.playerId())) continue;
            FormationData forced = new FormationData();
            forced.setPositionIndex(slot.positionIndex());
            forced.setPlayerId(slot.playerId());
            result.add(forced);
        }
        result.sort(Comparator.comparingInt(FormationData::getPositionIndex).thenComparingLong(FormationData::getPlayerId));
        return List.copyOf(result);
    }

    public List<EffectiveChairmanMandate.Slot> eligibleSlots(long teamId, Set<Long> unavailableIds) {
        return eligibleSlots(teamId, mandate(teamId), unavailableIds == null ? Set.of() : unavailableIds, true);
    }

    private List<EffectiveChairmanMandate.Slot> eligibleSlots(long teamId, EffectiveChairmanMandate current,
                                                               Set<Long> unavailableIds, boolean runtime) {
        List<EffectiveChairmanMandate.Slot> eligible = new ArrayList<>();
        Set<Integer> validPositions = validGrid(current.requiredFormation(), current.requiredFormation() != null);
        for (EffectiveChairmanMandate.Slot slot : current.lockedSlots()) {
            Human player = humanRepository.findById(slot.playerId()).orElse(null);
            if (player == null || player.getTeamId() == null || player.getTeamId() != teamId
                    || player.getTypeId() != TypeNames.PLAYER_TYPE || player.isRetired()) {
                if (!runtime) throw invalid("MANDATED_PLAYER_NOT_IN_TEAM", "Mandated player is not in the team");
                continue;
            }
            if (unavailableIds.contains(slot.playerId())) continue;
            if (slot.positionIndex() < 0 || slot.positionIndex() >= 30) {
                if (!runtime) throw invalid("MANDATED_POSITION_INVALID", "Mandated position is not a pitch slot");
                continue;
            }
            if (current.requiredFormation() != null && !validPositions.contains(slot.positionIndex())) {
                if (!runtime) throw invalid("MANDATED_POSITION_INVALID", "Mandated position is not in the required formation");
                continue;
            }
            eligible.add(slot);
        }
        return List.copyOf(eligible);
    }

    private Set<Integer> validGrid(String formation, boolean exact) {
        if (formation == null || formation.isBlank()) return Set.of();
        try {
            if (exact && !tacticService.isKnownFormation(formation)) {
                throw invalid("TACTICAL_MANDATE_INVALID", "Mandated formation is unknown");
            }
            return Arrays.stream(exact ? tacticService.getFormationGridIndicesExact(formation)
                    : tacticService.getFormationGridIndices(formation)).boxed().collect(Collectors.toSet());
        } catch (ChairmanTacticalMandateException e) {
            throw e;
        } catch (RuntimeException e) {
            if (exact) throw invalid("TACTICAL_MANDATE_INVALID", "Mandated formation is invalid");
            return Set.of();
        }
    }

    private static FormationData copy(FormationData source) {
        FormationData copy = new FormationData();
        copy.setPositionIndex(source.getPositionIndex());
        copy.setPlayerId(source.getPlayerId());
        copy.setRole(source.getRole());
        copy.setDuty(source.getDuty());
        copy.setInstructions(source.getInstructions() == null ? null : List.copyOf(source.getInstructions()));
        return copy;
    }

    private static ChairmanTacticalMandateException invalid(String code, String message) {
        return new ChairmanTacticalMandateException(code, message);
    }
}
