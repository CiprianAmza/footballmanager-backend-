package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed entity-free baseline and opponent fixtures used by opt-in long tests. */
public final class CalibrationScenarioFixtures {
    private CalibrationScenarioFixtures() {}

    public static ScoringSensitivityScenario baseline200Season() {
        return new ScoringSensitivityScenario("baseline-200-seasons", team(1, Mentality.BALANCED, 15),
                team(1001, Mentality.BALANCED, 14), 13_071_991L, 200);
    }

    public static ScoringSensitivityScenario selectedWeights() {
        return new ScoringSensitivityScenario("selected-weights", team(1, Mentality.BALANCED, 15),
                team(1001, Mentality.BALANCED, 14), 13_071_991L, 200);
    }

    public static ScoringSensitivityScenario allWeights() {
        return new ScoringSensitivityScenario("all-weights", team(1, Mentality.BALANCED, 15),
                team(1001, Mentality.BALANCED, 14), 13_071_991L, 200);
    }

    /**
     * Twenty-club calibration league with the candidate at index zero and a
     * symmetric distribution of weaker/equal/stronger opponents. Ranking, not a
     * hard-coded points total, defines whether the candidate is genuinely mid-table.
     */
    public static List<CalibrationTeam> midTableLeague() {
        int[] strengths = {15, 10, 11, 12, 13, 14, 10, 11, 12, 13,
                15, 16, 17, 18, 19, 20, 16, 17, 18, 19};
        java.util.ArrayList<CalibrationTeam> teams = new java.util.ArrayList<>(strengths.length);
        for (int index = 0; index < strengths.length; index++) {
            teams.add(team(1L + index * 100L, Mentality.BALANCED, strengths[index]));
        }
        return List.copyOf(teams);
    }

    private static CalibrationTeam team(long firstId, Mentality mentality, int value) {
        List<PlayerPosition> positions = List.of(PlayerPosition.GK, PlayerPosition.DC, PlayerPosition.DC,
                PlayerPosition.DL, PlayerPosition.DR, PlayerPosition.DM, PlayerPosition.DM,
                PlayerPosition.AML, PlayerPosition.AMC, PlayerPosition.AMR, PlayerPosition.ST);
        List<PlayerRole> roles = List.of(PlayerRole.GOALKEEPER, PlayerRole.CENTRAL_DEFENDER,
                PlayerRole.BALL_PLAYING_DEFENDER, PlayerRole.FULL_BACK, PlayerRole.FULL_BACK,
                PlayerRole.DEEP_LYING_PLAYMAKER, PlayerRole.CENTRAL_MIDFIELDER, PlayerRole.WINGER,
                PlayerRole.ADVANCED_PLAYMAKER, PlayerRole.WINGER, PlayerRole.POACHER);
        List<Duty> duties = List.of(Duty.DEFEND, Duty.DEFEND, Duty.SUPPORT, Duty.SUPPORT, Duty.SUPPORT,
                Duty.DEFEND, Duty.SUPPORT, Duty.ATTACK, Duty.ATTACK, Duty.ATTACK, Duty.ATTACK);
        List<CalibrationPlayer> players = new java.util.ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            EnumMap<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
            for (PlayerAttribute attribute : PlayerAttribute.values()) attributes.put(attribute, value);
            EnumMap<PlayerAttribute, Double> roleWeights = new EnumMap<>(PlayerAttribute.class);
            for (PlayerAttribute attribute : PlayerAttribute.values()) roleWeights.put(attribute, 1.0 / PlayerAttribute.values().length);
            Map<PlayerPosition, Integer> familiarity = Map.of(positions.get(i), 20);
            Map<com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey, Integer> roleFamiliarity =
                    Map.of(new com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey(positions.get(i), roles.get(i)), 20);
            PlayerPosition currentPosition = positions.get(i);
            int occurrence = (int) positions.subList(0, i).stream().filter(position -> position == currentPosition).count() + 1;
            players.add(new CalibrationPlayer(firstId + i, positions.get(i), occurrence,
                    roles.get(i), duties.get(i), attributes, roleWeights, 100, 70, familiarity, roleFamiliarity,
                    10, 10, Set.of(), ForwardInstruction.DEFAULT,
                    new TacticalContextInput(mentality.name().replace('_', ' '), "Standard", "Normal", "Standard", "Standard", "Balanced", List.of())));
        }
        return new CalibrationTeam(mentality, players);
    }
}
