package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure deterministic bridge from canonical lineup players to team aggregation. */
public final class CanonicalTeamEvaluationAdapter {
    private final CanonicalPlayerContextAdapter playerAdapter;
    private final TeamCompartmentAggregator aggregator;

    public CanonicalTeamEvaluationAdapter(CompartmentEngineConfig compartmentConfig,
                                          MatchEngineConfig matchEngineConfig) {
        this.playerAdapter = new CanonicalPlayerContextAdapter(compartmentConfig, matchEngineConfig);
        this.aggregator = new TeamCompartmentAggregator(Objects.requireNonNull(
                compartmentConfig, "compartmentConfig"));
    }

    public CanonicalTeamEvaluation evaluate(Mentality mentality,
                                             Collection<CanonicalLineupPlayer> lineup,
                                             Map<Long, TacticalContextInput> tacticalContextByPlayerId) {
        Objects.requireNonNull(mentality, "mentality");
        Objects.requireNonNull(lineup, "lineup");
        Objects.requireNonNull(tacticalContextByPlayerId, "tacticalContextByPlayerId");
        if (lineup.isEmpty()) throw new IllegalArgumentException("lineup must not be empty");

        List<CanonicalLineupPlayer> orderedLineup = new ArrayList<>(lineup);
        if (orderedLineup.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("lineup cannot contain null values");
        }
        Set<Long> playerIds = new HashSet<>();
        for (CanonicalLineupPlayer player : orderedLineup) {
            if (!playerIds.add(player.playerId())) {
                throw new IllegalArgumentException("duplicate player id: " + player.playerId());
            }
        }
        if (!tacticalContextByPlayerId.keySet().equals(playerIds)) {
            throw new IllegalArgumentException("tactical context must contain exactly one entry per player");
        }

        Map<Long, CanonicalPlayerEvaluation> evaluationsById = new LinkedHashMap<>();
        List<TeamCompartmentAggregator.PlayerCompartmentInput> inputs = new ArrayList<>();
        Set<String> passingValues = new java.util.LinkedHashSet<>();
        Set<String> pressingValues = new java.util.LinkedHashSet<>();
        Set<String> recoveryValues = new java.util.LinkedHashSet<>();
        for (CanonicalLineupPlayer player : orderedLineup) {
            TacticalContextInput context = tacticalContextByPlayerId.get(player.playerId());
            if (context == null) throw new IllegalArgumentException("tactical context cannot be null");
            pressingValues.add(context.pressing());
            passingValues.add(context.passingType());
            recoveryValues.add(context.recovery());
            CanonicalPlayerEvaluation evaluation = playerAdapter.evaluate(player, context);
            evaluationsById.put(player.playerId(), evaluation);
            inputs.add(new TeamCompartmentAggregator.PlayerCompartmentInput(
                    player.playerId(),
                    new TeamCompartmentAggregator.LineupSlot(player.usedPosition(), player.occurrence()),
                    evaluation.rating(),
                    new ArrayList<>(player.traits()),
                    player.forwardInstruction(),
                    player.overallRating(),
                    requiredAttribute(player, com.footballmanagergamesimulator.compartment.PlayerAttribute.LONG_SHOTS),
                    requiredAttribute(player, com.footballmanagergamesimulator.compartment.PlayerAttribute.POSITIONING),
                    requiredAttribute(player, com.footballmanagergamesimulator.compartment.PlayerAttribute.FINISHING),
                    requiredAttribute(player, com.footballmanagergamesimulator.compartment.PlayerAttribute.PACE),
                    requiredAttribute(player, com.footballmanagergamesimulator.compartment.PlayerAttribute.BALL_RECOVERY),
                    requiredAttribute(player, com.footballmanagergamesimulator.compartment.PlayerAttribute.TACKLING)));
        }

        if (passingValues.size() != 1 || pressingValues.size() != 1 || recoveryValues.size() != 1) {
            throw new IllegalArgumentException("all players must share the same team tactic axes");
        }
        TeamCompartmentAggregator.TeamAggregationResult team = aggregator.aggregate(
                mentality, passingValues.iterator().next(), pressingValues.iterator().next(),
                recoveryValues.iterator().next(), inputs);
        return new CanonicalTeamEvaluation(new ArrayList<>(evaluationsById.values()), team);
    }

    private static int requiredAttribute(CanonicalLineupPlayer player,
                                         com.footballmanagergamesimulator.compartment.PlayerAttribute attribute) {
        Integer value = player.attributes().get(attribute);
        if (value == null) {
            throw new IllegalArgumentException("missing " + attribute + " for player " + player.playerId());
        }
        return value;
    }
}
