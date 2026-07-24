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

/** Single canonical Chairman -> legacy -> manager selection policy. */
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
        EffectiveChairmanMandate current = mandate(teamId);
        List<String> canonical = tacticService.getAllExistingTactics();
        if (current.requiredFormation() != null) {
            if (!canonical.contains(current.requiredFormation())) {
                throw invalid("TACTICAL_MANDATE_INVALID", "Mandated formation is not canonical");
            }
            if (!containsAllLocks(current.requiredFormation(), current)) {
                throw invalid("MANDATE_SLOT_NOT_IN_FORMATION", "Mandated slot is not in formation");
            }
            return current.requiredFormation();
        }
        if (current.lockedSlots().isEmpty()) return proposedFormation;
        if (canonical.contains(proposedFormation) && containsAllLocks(proposedFormation, current)) return proposedFormation;
        return canonical.stream()
                .filter(formation -> containsAllLocks(formation, current))
                .findFirst().orElseThrow(() -> invalid("TACTICAL_MANDATE_INVALID",
                        "No canonical formation contains all active locks"));
    }

    private boolean containsAllLocks(String formation, EffectiveChairmanMandate current) {
        if (formation == null) return false;
        try {
            Set<Integer> grid = Arrays.stream(tacticService.getFormationGridIndicesExact(formation))
                    .boxed().collect(Collectors.toSet());
            return current.lockedSlots().stream().allMatch(lock -> grid.contains(lock.positionIndex()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Returns the exact effective lock placements in precedence order. The same
     * placement list is consumed by the controller and the simulator.
     */
    public List<EffectiveChairmanMandate.Slot> resolvedLockedSlots(
            long teamId, String proposedFormation,
            List<CoachPermissionService.LockedSlot> legacyLocks,
            Set<Long> unavailableIds) {
        return resolvedLocks(teamId, proposedFormation, legacyLocks, unavailableIds).stream()
                .map(ResolvedLock::slot).toList();
    }

    /** Runtime/edit enforcement over a defensive copy of submitted formation data. */
    public List<FormationData> enforceFormation(long teamId, String proposedFormation,
                                                List<FormationData> submitted,
                                                List<CoachPermissionService.LockedSlot> legacyLocks,
                                                Set<Long> unavailableIds, boolean runtime) {
        EffectiveChairmanMandate current = mandate(teamId);
        Set<Long> unavailable = unavailableIds == null ? Set.of() : Set.copyOf(unavailableIds);
        boolean active = current.requiredFormation() != null || !current.lockedSlots().isEmpty();
        String effectiveFormation = active ? effectiveFormation(teamId, proposedFormation) : proposedFormation;
        Set<Integer> grid = validGrid(effectiveFormation, active);
        if (!runtime) {
            for (EffectiveChairmanMandate.Slot lock : current.lockedSlots()) {
                if (eligiblePlayer(teamId, lock.playerId(), unavailable) == null) {
                    throw invalid("MANDATED_PLAYER_NOT_IN_TEAM", "Mandated player is not in the controlled team");
                }
            }
        }
        List<ResolvedLock> locks = resolvedLocks(teamId, effectiveFormation, legacyLocks, unavailable);
        Map<Long, ResolvedLock> lockByPlayer = locks.stream().collect(Collectors.toMap(
                lock -> lock.slot().playerId(), lock -> lock, (left, right) -> left, LinkedHashMap::new));
        Map<Integer, ResolvedLock> lockByPosition = locks.stream().collect(Collectors.toMap(
                lock -> lock.slot().positionIndex(), lock -> lock, (left, right) -> left, LinkedHashMap::new));

        List<FormationData> submittedCopy = submitted == null ? List.of() : submitted.stream()
                .map(value -> value == null ? null : copy(value)).toList();
        Map<ResolvedLock, FormationData> exactContexts = new HashMap<>();
        List<FormationData> managerEntries = new ArrayList<>();
        Set<Integer> seenPositions = new HashSet<>();
        Set<Long> seenPlayers = new HashSet<>();
        for (FormationData value : submittedCopy) {
            if (value == null || value.getPlayerId() <= 0) {
                if (!runtime) throw invalid("MANAGER_XI_INVALID", "Formation contains an invalid player");
                continue;
            }
            if (!runtime && active && eligiblePlayer(teamId, value.getPlayerId(), Set.of()) == null) {
                throw invalid("MANAGER_XI_INVALID", "Formation player is not eligible for this team");
            }
            if (runtime && unavailable.contains(value.getPlayerId())) continue;

            ResolvedLock byPlayer = lockByPlayer.get(value.getPlayerId());
            ResolvedLock byPosition = lockByPosition.get(value.getPositionIndex());
            if (byPlayer != null) {
                if (value.getPositionIndex() == byPlayer.slot().positionIndex()) exactContexts.putIfAbsent(byPlayer, value);
                continue;
            }
            if (byPosition != null) continue;
            if (value.getPositionIndex() < 0 || value.getPositionIndex() > 36) {
                if (!runtime) throw invalid("MANAGER_XI_INVALID", "Formation slot is outside pitch and bench bounds");
                continue;
            }
            if (value.getPositionIndex() < 30 && active
                    && !grid.contains(value.getPositionIndex())) continue;
            if (!seenPositions.add(value.getPositionIndex()) || !seenPlayers.add(value.getPlayerId())) {
                if (!runtime && active) {
                    throw invalid("MANAGER_XI_INVALID", "Formation contains duplicate slots or players");
                }
                continue;
            }
            managerEntries.add(value);
        }

        List<FormationData> result = new ArrayList<>();
        Set<Integer> resultPositions = new HashSet<>();
        Set<Long> resultPlayers = new HashSet<>();
        for (ResolvedLock lock : locks) {
            FormationData value = exactContexts.get(lock);
            if (value == null) {
                value = new FormationData();
                value.setPositionIndex(lock.slot().positionIndex());
                value.setPlayerId(lock.slot().playerId());
            }
            if (addWithinBounds(value, result, resultPositions, resultPlayers)) continue;
        }
        for (FormationData value : managerEntries) {
            addWithinBounds(value, result, resultPositions, resultPlayers);
        }
        result.sort(Comparator.comparingInt(FormationData::getPositionIndex).thenComparingLong(FormationData::getPlayerId));
        return List.copyOf(result);
    }

    /**
     * Preserves the already-valid manager choices and fills only missing slots
     * from the deterministic assistant result. This is the sole completion
     * policy shared by human runtime, saved lineups and read models.
     */
    public List<FormationData> completeFormation(List<FormationData> preserved,
                                                  List<FormationData> assistant) {
        List<FormationData> result = new ArrayList<>();
        Set<Integer> positions = new HashSet<>();
        Set<Long> players = new HashSet<>();
        addUniqueBounded(preserved, result, positions, players);
        addUniqueBounded(assistant, result, positions, players);
        result.sort(Comparator.comparingInt(FormationData::getPositionIndex)
                .thenComparingLong(FormationData::getPlayerId));
        return List.copyOf(result);
    }

    private static void addUniqueBounded(List<FormationData> source, List<FormationData> result,
                                         Set<Integer> positions, Set<Long> players) {
        if (source == null) return;
        for (FormationData value : source) {
            if (value == null) continue;
            int position = value.getPositionIndex();
            long starters = result.stream().filter(item -> item.getPositionIndex() < 30).count();
            long bench = result.stream().filter(item -> item.getPositionIndex() >= 30).count();
            if (position < 0 || position > 36 || position < 30 && starters >= 11
                    || position >= 30 && bench >= 7 || !positions.add(position)
                    || !players.add(value.getPlayerId())) continue;
            result.add(copy(value));
        }
    }

    public List<EffectiveChairmanMandate.Slot> eligibleSlots(long teamId, Set<Long> unavailableIds) {
        return resolvedLockedSlots(teamId, mandate(teamId).requiredFormation(), List.of(), unavailableIds);
    }

    private List<ResolvedLock> resolvedLocks(long teamId, String proposedFormation,
                                             List<CoachPermissionService.LockedSlot> legacyLocks,
                                             Set<Long> unavailableIds) {
        EffectiveChairmanMandate current = mandate(teamId);
        boolean active = current.requiredFormation() != null || !current.lockedSlots().isEmpty();
        String effectiveFormation = active ? effectiveFormation(teamId, proposedFormation) : proposedFormation;
        Set<Integer> grid = validGrid(effectiveFormation, active);
        List<ResolvedLock> result = new ArrayList<>();
        for (EffectiveChairmanMandate.Slot lock : current.lockedSlots()) {
            if (eligiblePlayer(teamId, lock.playerId(), unavailableIds) == null) continue;
            if (!grid.contains(lock.positionIndex())) {
                throw invalid("MANDATE_SLOT_NOT_IN_FORMATION", "Mandated slot is not in formation");
            }
            if (result.stream().anyMatch(existing -> conflicts(existing.slot(), lock))) {
                throw invalid("DUPLICATE_MANDATE_SLOT", "Mandated lock conflicts with another lock");
            }
            result.add(new ResolvedLock(lock, true));
        }
        for (CoachPermissionService.LockedSlot legacy : legacyLocks == null
                ? List.<CoachPermissionService.LockedSlot>of() : legacyLocks) {
            if (legacy == null) continue;
            EffectiveChairmanMandate.Slot raw = new EffectiveChairmanMandate.Slot(legacy.positionIndex(), legacy.playerId());
            if (result.stream().anyMatch(existing -> conflicts(existing.slot(), raw))
                    || eligiblePlayer(teamId, raw.playerId(), unavailableIds) == null) continue;
            if (!grid.contains(raw.positionIndex())) continue;
            if (result.stream().anyMatch(existing -> conflicts(existing.slot(), raw))) continue;
            result.add(new ResolvedLock(raw, false));
        }
        return result;
    }

    private boolean conflicts(EffectiveChairmanMandate.Slot left, EffectiveChairmanMandate.Slot right) {
        return left.positionIndex() == right.positionIndex() || left.playerId() == right.playerId();
    }

    private Human eligiblePlayer(long teamId, long playerId, Set<Long> unavailable) {
        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null || player.getTeamId() == null || player.getTeamId() != teamId
                || player.getTypeId() != TypeNames.PLAYER_TYPE || player.isRetired()
                || unavailable.contains(playerId)) return null;
        return player;
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

    private static boolean addWithinBounds(FormationData value, List<FormationData> result,
                                           Set<Integer> positions, Set<Long> players) {
        int position = value.getPositionIndex();
        long starters = result.stream().filter(v -> v.getPositionIndex() < 30).count();
        long bench = result.stream().filter(v -> v.getPositionIndex() >= 30 && v.getPositionIndex() <= 36).count();
        if (position < 0 || position > 36 || position < 30 && starters >= 11
                || position >= 30 && position <= 36 && bench >= 7
                || !positions.add(position) || !players.add(value.getPlayerId())) return false;
        result.add(copy(value));
        return true;
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

    private record ResolvedLock(EffectiveChairmanMandate.Slot slot, boolean chairman) { }

    private static ChairmanTacticalMandateException invalid(String code, String message) {
        return new ChairmanTacticalMandateException(code, message);
    }
}
