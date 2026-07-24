package com.footballmanagergamesimulator.chairman.mandate;

import java.util.*;

/** Pure overlay of a canonical mandate on a manager-proposed XI. */
public final class ChairmanTacticalMandateResolver {
    public record ProposedSlot(int positionIndex, long playerId) { }
    public record Mandate(String requiredFormation, List<ProposedSlot> lockedSlots) {
        public Mandate {
            lockedSlots = List.copyOf(lockedSlots == null ? List.of() : lockedSlots);
        }
    }
    public record ResolvedXI(String formation, List<ProposedSlot> slots) {
        public ResolvedXI {
            slots = List.copyOf(slots);
        }
    }

    private final Map<String, Set<Integer>> grids;

    public ChairmanTacticalMandateResolver(Map<String, int[]> formationGrids) {
        Map<String, Set<Integer>> copy = new LinkedHashMap<>();
        formationGrids.forEach((formation, grid) -> {
            Set<Integer> indices = new TreeSet<>();
            for (int index : grid) indices.add(index);
            copy.put(formation, Collections.unmodifiableSet(indices));
        });
        grids = Collections.unmodifiableMap(copy);
    }

    public ResolvedXI resolve(String managerFormation, List<ProposedSlot> managerXI, Mandate mandate) {
        Objects.requireNonNull(mandate, "mandate");
        String effective = mandate.requiredFormation() != null ? mandate.requiredFormation() : managerFormation;
        Set<Integer> valid = grids.get(effective);
        if (valid == null) throw error("FORMATION_NOT_FOUND", "Formation is not known: " + effective);

        validateUnique(managerXI, "MANAGER_XI_INVALID");
        Set<Integer> imposedPositions = new HashSet<>();
        Set<Long> imposedPlayers = new HashSet<>();
        for (ProposedSlot slot : mandate.lockedSlots()) {
            if (!valid.contains(slot.positionIndex())) throw error("MANDATE_SLOT_NOT_IN_FORMATION", "Mandated slot is not in formation");
            if (!imposedPositions.add(slot.positionIndex())) throw error("DUPLICATE_MANDATE_SLOT", "Mandated slot is duplicated");
            if (!imposedPlayers.add(slot.playerId())) throw error("DUPLICATE_MANDATE_PLAYER", "Mandated player is duplicated");
        }

        List<ProposedSlot> result = new ArrayList<>();
        for (ProposedSlot slot : managerXI) {
            if (!imposedPositions.contains(slot.positionIndex()) && !imposedPlayers.contains(slot.playerId())) result.add(slot);
        }
        result.addAll(mandate.lockedSlots());
        validateUnique(result, "MANAGER_XI_INVALID");
        if (result.size() > 11) throw error("MANAGER_XI_INVALID", "XI cannot contain more than 11 players");
        if (result.stream().anyMatch(slot -> !valid.contains(slot.positionIndex()))) {
            throw error("MANDATE_SLOT_NOT_IN_FORMATION", "XI slot is not in formation");
        }
        result.sort(Comparator.comparingInt(ProposedSlot::positionIndex).thenComparingLong(ProposedSlot::playerId));
        return new ResolvedXI(effective, result);
    }

    private static void validateUnique(List<ProposedSlot> slots, String code) {
        Set<Integer> positions = new HashSet<>();
        Set<Long> players = new HashSet<>();
        for (ProposedSlot slot : slots) {
            if (!positions.add(slot.positionIndex())) throw error(code, "Duplicate XI slot");
            if (!players.add(slot.playerId())) throw error(code, "Duplicate XI player");
        }
    }

    private static ChairmanTacticalMandateException error(String code, String message) {
        return new ChairmanTacticalMandateException(code, message);
    }
}
