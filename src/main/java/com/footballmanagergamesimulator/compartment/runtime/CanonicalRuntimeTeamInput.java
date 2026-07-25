package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable canonical runtime input ready for Phase 6 evaluation. */
public record CanonicalRuntimeTeamInput(
        Mentality mentality,
        List<CanonicalLineupPlayer> lineup,
        Map<Long, TacticalContextInput> tacticalContexts) {

    private static final Comparator<CanonicalLineupPlayer> ORDER =
            Comparator.comparing(CanonicalLineupPlayer::usedPosition)
                    .thenComparingInt(CanonicalLineupPlayer::occurrence)
                    .thenComparingLong(CanonicalLineupPlayer::playerId);

    public CanonicalRuntimeTeamInput {
        mentality = Objects.requireNonNull(mentality, "mentality");
        Objects.requireNonNull(lineup, "lineup");
        Objects.requireNonNull(tacticalContexts, "tacticalContexts");
        List<CanonicalLineupPlayer> ordered = new ArrayList<>(lineup);
        if (ordered.size() != 11) {
            throw new IllegalArgumentException("lineup must contain exactly 11 players");
        }
        if (ordered.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("lineup cannot contain null players");
        }
        ordered.sort(ORDER);

        Set<Long> playerIds = new HashSet<>();
        Set<SlotKey> slots = new HashSet<>();
        long goalkeepers = 0;
        for (CanonicalLineupPlayer player : ordered) {
            if (!playerIds.add(player.playerId())) {
                throw new IllegalArgumentException("duplicate player id: " + player.playerId());
            }
            if (!slots.add(new SlotKey(player.usedPosition(), player.occurrence()))) {
                throw new IllegalArgumentException("duplicate lineup slot");
            }
            if (player.usedPosition() == PlayerPosition.GK) goalkeepers++;
        }
        if (goalkeepers != 1) {
            throw new IllegalArgumentException("lineup must contain exactly one goalkeeper");
        }
        if (!tacticalContexts.keySet().equals(playerIds)) {
            throw new IllegalArgumentException("tactical context keys must match lineup player ids");
        }
        LinkedHashMap<Long, TacticalContextInput> orderedContexts = new LinkedHashMap<>();
        for (CanonicalLineupPlayer player : ordered) {
            TacticalContextInput context = tacticalContexts.get(player.playerId());
            if (context == null) throw new IllegalArgumentException("tactical context cannot be null");
            orderedContexts.put(player.playerId(), context);
        }
        lineup = List.copyOf(ordered);
        tacticalContexts = Collections.unmodifiableMap(orderedContexts);
    }

    private record SlotKey(PlayerPosition position, int occurrence) {}
}
