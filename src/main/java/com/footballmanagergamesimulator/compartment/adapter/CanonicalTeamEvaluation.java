package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable explainable result of one canonical lineup team evaluation. */
public record CanonicalTeamEvaluation(
        List<CanonicalPlayerEvaluation> players,
        TeamCompartmentAggregator.TeamAggregationResult team) {

    private static final Comparator<CanonicalPlayerEvaluation> ORDER =
            Comparator.comparing(CanonicalPlayerEvaluation::usedPosition)
                    .thenComparingInt(CanonicalPlayerEvaluation::occurrence)
                    .thenComparingLong(CanonicalPlayerEvaluation::playerId);

    public CanonicalTeamEvaluation {
        Objects.requireNonNull(players, "players");
        team = Objects.requireNonNull(team, "team");
        List<CanonicalPlayerEvaluation> copy = new ArrayList<>(players);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("players cannot contain null values");
        }
        copy.sort(ORDER);
        players = List.copyOf(copy);
    }
}
