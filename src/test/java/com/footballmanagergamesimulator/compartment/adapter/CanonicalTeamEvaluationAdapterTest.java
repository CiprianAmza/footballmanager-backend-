package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalTeamEvaluationAdapterTest {
    private final CompartmentEngineConfig compartmentConfig = AdapterTestFixture.loadConfig();
    private final MatchEngineConfig matchConfig = new MatchEngineConfig();
    private final CanonicalTeamEvaluationAdapter adapter =
            new CanonicalTeamEvaluationAdapter(compartmentConfig, matchConfig);

    @Test
    void validElevenPlayerLineupProducesDeterministicTeamAndPlayerOrder() {
        List<CanonicalLineupPlayer> lineup = lineup();
        Map<Long, TacticalContextInput> contexts = contexts(lineup);

        CanonicalTeamEvaluation first = adapter.evaluate(Mentality.BALANCED, lineup, contexts);
        List<CanonicalLineupPlayer> reversed = new ArrayList<>(lineup);
        Collections.reverse(reversed);
        Map<Long, TacticalContextInput> reversedContexts = new java.util.LinkedHashMap<>();
        List<Map.Entry<Long, TacticalContextInput>> contextEntries = new ArrayList<>(contexts.entrySet());
        Collections.reverse(contextEntries);
        contextEntries.forEach(entry -> reversedContexts.put(entry.getKey(), entry.getValue()));
        CanonicalTeamEvaluation second = adapter.evaluate(Mentality.BALANCED, reversed, reversedContexts);

        assertThat(first.team()).isEqualTo(second.team());
        assertThat(first.players()).isEqualTo(second.players());
        assertThat(first.players().stream().map(CanonicalPlayerEvaluation::playerId))
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
    }

    @Test
    void contextsMustMatchPlayersExactlyAndPlayerIdsMustBeUnique() {
        List<CanonicalLineupPlayer> lineup = lineup();
        Map<Long, TacticalContextInput> contexts = contexts(lineup);
        Map<Long, TacticalContextInput> missing = new java.util.LinkedHashMap<>(contexts);
        missing.remove(1L);
        assertThatThrownBy(() -> adapter.evaluate(Mentality.BALANCED, lineup, missing))
                .isInstanceOf(IllegalArgumentException.class);
        Map<Long, TacticalContextInput> extra = new java.util.LinkedHashMap<>(contexts);
        extra.put(99L, TacticalContextInput.neutral());
        assertThatThrownBy(() -> adapter.evaluate(Mentality.BALANCED, lineup, extra))
                .isInstanceOf(IllegalArgumentException.class);

        List<CanonicalLineupPlayer> duplicate = new ArrayList<>(lineup);
        duplicate.set(1, copyWithId(lineup.get(1), 1L));
        assertThatThrownBy(() -> adapter.evaluate(Mentality.BALANCED, duplicate, contexts))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregatorRejectsDuplicateSlotsAndInvalidGoalkeeperCounts() {
        List<CanonicalLineupPlayer> lineup = lineup();
        List<CanonicalLineupPlayer> duplicateSlot = new ArrayList<>(lineup);
        duplicateSlot.set(2, copyAt(duplicateSlot.get(2), PlayerPosition.DC, 1));
        assertThatThrownBy(() -> adapter.evaluate(Mentality.BALANCED, duplicateSlot, contexts(duplicateSlot)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate lineup slot");

        List<CanonicalLineupPlayer> noGoalkeeper = lineup.stream()
                .map(player -> player.usedPosition() == PlayerPosition.GK
                        ? copyAt(player, PlayerPosition.WBL, 1) : player)
                .toList();
        assertThatThrownBy(() -> adapter.evaluate(Mentality.BALANCED, noGoalkeeper, contexts(noGoalkeeper)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one goalkeeper");

        List<CanonicalLineupPlayer> twoGoalkeepers = new ArrayList<>(lineup);
        twoGoalkeepers.set(1, copyAt(twoGoalkeepers.get(1), PlayerPosition.GK, 2));
        assertThatThrownBy(() -> adapter.evaluate(Mentality.BALANCED, twoGoalkeepers, contexts(twoGoalkeepers)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one goalkeeper");
    }

    @Test
    void teamResultMatchesDirectAggregatorForTheSameCalculatedRatings() {
        List<CanonicalLineupPlayer> lineup = lineup();
        Map<Long, TacticalContextInput> contexts = contexts(lineup);
        CanonicalTeamEvaluation evaluation = adapter.evaluate(Mentality.ATTACKING, lineup, contexts);
        List<TeamCompartmentAggregator.PlayerCompartmentInput> inputs = evaluation.players().stream()
                .map(player -> new TeamCompartmentAggregator.PlayerCompartmentInput(
                        player.playerId(),
                        new TeamCompartmentAggregator.LineupSlot(player.usedPosition(), player.occurrence()),
                        player.rating(),
                        new ArrayList<>(lineup.stream().filter(source -> source.playerId() == player.playerId()).findFirst().orElseThrow().traits()),
                        lineup.stream().filter(source -> source.playerId() == player.playerId()).findFirst().orElseThrow().forwardInstruction()))
                .toList();
        TeamCompartmentAggregator.TeamAggregationResult expected =
                new TeamCompartmentAggregator(compartmentConfig).aggregate(Mentality.ATTACKING, inputs);
        assertThat(evaluation.team()).isEqualTo(expected);
    }

    private List<CanonicalLineupPlayer> lineup() {
        return List.of(
                player(1, PlayerPosition.GK, 1, PlayerRole.GOALKEEPER),
                player(2, PlayerPosition.DC, 1, PlayerRole.CENTRAL_DEFENDER),
                player(3, PlayerPosition.DL, 1, PlayerRole.FULL_BACK),
                player(4, PlayerPosition.DR, 1, PlayerRole.FULL_BACK),
                player(5, PlayerPosition.DM, 1, PlayerRole.BALL_WINNING_MIDFIELDER),
                player(6, PlayerPosition.MC, 1, PlayerRole.CENTRAL_MIDFIELDER),
                player(7, PlayerPosition.ML, 1, PlayerRole.WIDE_MIDFIELDER),
                player(8, PlayerPosition.MR, 1, PlayerRole.WINGER),
                player(9, PlayerPosition.AMC, 1, PlayerRole.ADVANCED_PLAYMAKER),
                player(10, PlayerPosition.AML, 1, PlayerRole.INSIDE_FORWARD),
                player(11, PlayerPosition.ST, 1, PlayerRole.POACHER));
    }

    private static CanonicalLineupPlayer player(long id, PlayerPosition position, int occurrence, PlayerRole role) {
        return new CanonicalLineupPlayer(id, position, occurrence, role, Duty.SUPPORT,
                attributes(15), 90, 70,
                new PlayerCapabilitySnapshot(id, position,
                        Map.of(position, 20), Map.of(new PositionRoleKey(position, role), 10),
                        8, 20, false, false, false),
                50, Set.of(), ForwardInstruction.DEFAULT);
    }

    private static CanonicalLineupPlayer copyAt(CanonicalLineupPlayer source, PlayerPosition position, int occurrence) {
        return new CanonicalLineupPlayer(source.playerId(), position, occurrence, roleFor(position), source.duty(),
                source.attributes(), source.fitness(), source.morale(),
                new PlayerCapabilitySnapshot(source.playerId(), position, Map.of(position, 20),
                        Map.of(new PositionRoleKey(position, roleFor(position)), 10), 8, 20, false, false, false),
                source.roleSuitability(), source.traits(), source.forwardInstruction());
    }

    private static CanonicalLineupPlayer copyWithId(CanonicalLineupPlayer source, long id) {
        return new CanonicalLineupPlayer(id, source.usedPosition(), source.occurrence(), source.role(), source.duty(),
                source.attributes(), source.fitness(), source.morale(),
                new PlayerCapabilitySnapshot(id, source.usedPosition(), Map.of(source.usedPosition(), 20),
                        Map.of(new PositionRoleKey(source.usedPosition(), source.role()), 10), 8, 20, false, false, false),
                source.roleSuitability(), source.traits(), source.forwardInstruction());
    }

    private static PlayerRole roleFor(PlayerPosition position) {
        return switch (position) {
            case GK -> PlayerRole.GOALKEEPER;
            case DC -> PlayerRole.CENTRAL_DEFENDER;
            case DL, DR, WBL, WBR -> PlayerRole.FULL_BACK;
            case DM -> PlayerRole.BALL_WINNING_MIDFIELDER;
            case MC -> PlayerRole.CENTRAL_MIDFIELDER;
            case ML, MR -> PlayerRole.WIDE_MIDFIELDER;
            case AMC -> PlayerRole.ADVANCED_PLAYMAKER;
            case AML, AMR -> PlayerRole.INSIDE_FORWARD;
            case ST -> PlayerRole.POACHER;
        };
    }

    private static Map<Long, TacticalContextInput> contexts(List<CanonicalLineupPlayer> lineup) {
        Map<Long, TacticalContextInput> contexts = new java.util.LinkedHashMap<>();
        for (CanonicalLineupPlayer player : lineup) contexts.put(player.playerId(), TacticalContextInput.neutral());
        return contexts;
    }

    private static Map<PlayerAttribute, Integer> attributes(int value) {
        EnumMap<PlayerAttribute, Integer> result = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute attribute : PlayerAttribute.values()) result.put(attribute, value);
        return result;
    }
}
