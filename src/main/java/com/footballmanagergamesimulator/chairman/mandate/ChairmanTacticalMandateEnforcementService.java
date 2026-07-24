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
        String required = mandate(teamId).requiredFormation();
        return required != null ? required : proposedFormation;
    }

    /**
     * Returns the exact effective lock placements in precedence order. The same
     * placement list is consumed by the controller and the simulator.
     */
    public List<EffectiveChairmanMandate.Slot> resolvedLockedSlots(
            long teamId, String proposedFormation,
            List<CoachPermissionService.LockedSlot> legacyLocks,
            Set<Long> unavailableIds) {
        return placedLocks(teamId, proposedFormation, legacyLocks, unavailableIds).stream()
                .map(PlacedLock::placed).toList();
    }

    /** Runtime/edit enforcement over a defensive copy of submitted formation data. */
    public List<FormationData> enforceFormation(long teamId, String proposedFormation,
                                                List<FormationData> submitted,
                                                List<CoachPermissionService.LockedSlot> legacyLocks,
                                                Set<Long> unavailableIds, boolean runtime) {
        EffectiveChairmanMandate current = mandate(teamId);
        Set<Long> unavailable = unavailableIds == null ? Set.of() : Set.copyOf(unavailableIds);
        String effectiveFormation = current.requiredFormation() != null
                ? current.requiredFormation() : proposedFormation;
        Set<Integer> grid = validGrid(effectiveFormation, current.requiredFormation() != null);
        if (!runtime) {
            for (EffectiveChairmanMandate.Slot lock : current.lockedSlots()) {
                if (eligiblePlayer(teamId, lock.playerId(), unavailable) == null) {
                    throw invalid("MANDATED_PLAYER_NOT_IN_TEAM", "Mandated player is not in the controlled team");
                }
            }
        }
        List<PlacedLock> locks = placedLocks(teamId, proposedFormation, legacyLocks, unavailable);
        Map<Long, PlacedLock> lockByPlayer = locks.stream().collect(Collectors.toMap(
                lock -> lock.raw().playerId(), lock -> lock, (left, right) -> left, LinkedHashMap::new));
        Map<Integer, PlacedLock> lockByRawPosition = locks.stream().collect(Collectors.toMap(
                lock -> lock.raw().positionIndex(), lock -> lock, (left, right) -> left, LinkedHashMap::new));
        Map<Integer, PlacedLock> lockByPlacedPosition = locks.stream().collect(Collectors.toMap(
                lock -> lock.placed().positionIndex(), lock -> lock, (left, right) -> left, LinkedHashMap::new));

        List<FormationData> submittedCopy = submitted == null ? List.of() : submitted.stream()
                .map(value -> value == null ? null : copy(value)).toList();
        Map<PlacedLock, FormationData> exactContexts = new HashMap<>();
        List<FormationData> managerEntries = new ArrayList<>();
        Set<Integer> seenPositions = new HashSet<>();
        Set<Long> seenPlayers = new HashSet<>();
        for (FormationData value : submittedCopy) {
            if (value == null || value.getPlayerId() <= 0) {
                if (!runtime) throw invalid("MANAGER_XI_INVALID", "Formation contains an invalid player");
                continue;
            }
            if (runtime && unavailable.contains(value.getPlayerId())) continue;

            PlacedLock byPlayer = lockByPlayer.get(value.getPlayerId());
            PlacedLock byRawPosition = lockByRawPosition.get(value.getPositionIndex());
            PlacedLock byPlacedPosition = lockByPlacedPosition.get(value.getPositionIndex());
            if (byPlayer != null) {
                boolean exact = value.getPositionIndex() == byPlayer.raw().positionIndex()
                        || value.getPositionIndex() == byPlayer.placed().positionIndex();
                if (exact) {
                    value.setPositionIndex(byPlayer.placed().positionIndex());
                    exactContexts.putIfAbsent(byPlayer, value);
                }
                continue;
            }
            if (byRawPosition != null || byPlacedPosition != null) continue;
            if (value.getPositionIndex() < 0 || value.getPositionIndex() > 36) {
                if (!runtime) throw invalid("MANAGER_XI_INVALID", "Formation slot is outside pitch and bench bounds");
                continue;
            }
            if (value.getPositionIndex() < 30 && current.requiredFormation() != null
                    && !grid.contains(value.getPositionIndex())) continue;
            if (!seenPositions.add(value.getPositionIndex()) || !seenPlayers.add(value.getPlayerId())) {
                if (!runtime && (current.requiredFormation() != null || !locks.isEmpty())) {
                    throw invalid("MANAGER_XI_INVALID", "Formation contains duplicate slots or players");
                }
                continue;
            }
            managerEntries.add(value);
        }

        List<FormationData> result = new ArrayList<>();
        Set<Integer> resultPositions = new HashSet<>();
        Set<Long> resultPlayers = new HashSet<>();
        for (PlacedLock lock : locks) {
            FormationData value = exactContexts.get(lock);
            if (value == null) {
                value = new FormationData();
                value.setPositionIndex(lock.placed().positionIndex());
                value.setPlayerId(lock.placed().playerId());
            }
            if (addWithinBounds(value, result, resultPositions, resultPlayers)) continue;
        }
        for (FormationData value : managerEntries) {
            if (value.getPositionIndex() < 30 && result.size() >= 11) continue;
            if (value.getPositionIndex() >= 30 && result.stream().filter(v -> v.getPositionIndex() >= 30).count() >= 7) continue;
            addWithinBounds(value, result, resultPositions, resultPlayers);
        }
        result.sort(Comparator.comparingInt(FormationData::getPositionIndex).thenComparingLong(FormationData::getPlayerId));
        return List.copyOf(result);
    }

    public List<EffectiveChairmanMandate.Slot> eligibleSlots(long teamId, Set<Long> unavailableIds) {
        return resolvedLockedSlots(teamId, mandate(teamId).requiredFormation(), List.of(), unavailableIds);
    }

    private List<PlacedLock> placedLocks(long teamId, String proposedFormation,
                                         List<CoachPermissionService.LockedSlot> legacyLocks,
                                         Set<Long> unavailableIds) {
        EffectiveChairmanMandate current = mandate(teamId);
        Set<Integer> grid = validGrid(current.requiredFormation() != null
                ? current.requiredFormation() : proposedFormation, current.requiredFormation() != null);
        List<PlacedLock> result = new ArrayList<>();
        for (EffectiveChairmanMandate.Slot lock : current.lockedSlots()) {
            if (eligiblePlayer(teamId, lock.playerId(), unavailableIds) == null) continue;
            PlacedLock placed = place(new PlacedLock(lock, lock, true), grid, result);
            if (placed != null) result.add(placed);
        }
        for (CoachPermissionService.LockedSlot legacy : legacyLocks == null
                ? List.<CoachPermissionService.LockedSlot>of() : legacyLocks) {
            if (legacy == null) continue;
            EffectiveChairmanMandate.Slot raw = new EffectiveChairmanMandate.Slot(legacy.positionIndex(), legacy.playerId());
            if (conflictsWithChairman(raw, result)
                    || eligiblePlayer(teamId, raw.playerId(), unavailableIds) == null) continue;
            PlacedLock placed = place(new PlacedLock(raw, raw, false), grid, result);
            if (placed != null) result.add(placed);
        }
        return result;
    }

    private PlacedLock place(PlacedLock candidate, Set<Integer> grid, List<PlacedLock> occupied) {
        int rawPosition = candidate.raw().positionIndex();
        Set<Integer> taken = occupied.stream().map(lock -> lock.placed().positionIndex()).collect(Collectors.toSet());
        if (grid.contains(rawPosition) && !taken.contains(rawPosition)) return candidate;

        String rawBase = baseAt(rawPosition);
        List<Integer> candidates = grid.stream().filter(index -> !taken.contains(index))
                .sorted().toList();
        if ("GK".equals(rawBase)) {
            candidates = candidates.stream().filter(index -> "GK".equals(baseAt(index))).toList();
        }
        Integer replacement = candidates.stream().filter(index -> baseAt(index).equals(rawBase)).findFirst().orElse(null);
        if (replacement == null && !"GK".equals(rawBase)) {
            String compartment = compartment(rawBase);
            replacement = candidates.stream().filter(index -> compartment(baseAt(index)).equals(compartment)).findFirst().orElse(null);
        }
        if (replacement == null && !candidates.isEmpty() && !"GK".equals(rawBase)) {
            replacement = candidates.get(candidates.size() - 1);
        }
        if (replacement == null) return null;
        return new PlacedLock(candidate.raw(), new EffectiveChairmanMandate.Slot(
                replacement, candidate.placed().playerId()), candidate.chairman());
    }

    private boolean conflictsWithChairman(EffectiveChairmanMandate.Slot legacy, List<PlacedLock> chairman) {
        return chairman.stream().anyMatch(lock -> lock.chairman()
                && (lock.raw().positionIndex() == legacy.positionIndex()
                || lock.raw().playerId() == legacy.playerId()
                || lock.placed().positionIndex() == legacy.positionIndex()
                || lock.placed().playerId() == legacy.playerId()));
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

    private String baseAt(int index) {
        if (index < 0 || index >= 30) return "UNKNOWN";
        return TacticService.getBasePosition(tacticService.getPositionFromIndex(index));
    }

    private String compartment(String base) {
        if ("GK".equals(base)) return "GK";
        if (Set.of("DL", "DC", "DR").contains(base)) return "DEF";
        if (Set.of("DM", "ML", "MC", "MR").contains(base)) return "MID";
        return "ATT";
    }

    private static boolean addWithinBounds(FormationData value, List<FormationData> result,
                                           Set<Integer> positions, Set<Long> players) {
        int position = value.getPositionIndex();
        if (position < 0 || position > 36 || position < 30 && result.stream().filter(v -> v.getPositionIndex() < 30).count() >= 11
                || position >= 30 && result.stream().filter(v -> v.getPositionIndex() >= 30).count() >= 7
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

    private record PlacedLock(EffectiveChairmanMandate.Slot raw,
                              EffectiveChairmanMandate.Slot placed,
                              boolean chairman) { }

    private static ChairmanTacticalMandateException invalid(String code, String message) {
        return new ChairmanTacticalMandateException(code, message);
    }
}
