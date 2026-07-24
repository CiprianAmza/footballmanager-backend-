package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.TacticalScoreService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalScoringFingerprintServiceTest {
    private final CanonicalScoringFingerprintService service = new CanonicalScoringFingerprintService();

    @Test
    void configFingerprintIsOrderIndependentAndExcludesRolloutFlags() {
        CompartmentEngineConfig first = new CompartmentEngineConfig();
        CompartmentEngineConfig second = new CompartmentEngineConfig();
        first.getCompartments().put(com.footballmanagergamesimulator.compartment.Compartment.ATTACK,
                weights(PlayerAttribute.FINISHING, 1.0));
        first.getCompartments().put(com.footballmanagergamesimulator.compartment.Compartment.DEFENSE,
                weights(PlayerAttribute.TACKLING, 2.0));
        second.getCompartments().put(com.footballmanagergamesimulator.compartment.Compartment.DEFENSE,
                weights(PlayerAttribute.TACKLING, 2.0));
        second.getCompartments().put(com.footballmanagergamesimulator.compartment.Compartment.ATTACK,
                weights(PlayerAttribute.FINISHING, 1.0));
        second.setEnabled(true);
        second.setShadowEnabled(true);
        assertThat(service.configFingerprint(first, new MatchEngineConfig()))
                .isEqualTo(service.configFingerprint(second, new MatchEngineConfig()));

        second.getRating().setScoreScale(101.0);
        assertThat(service.configFingerprint(second, new MatchEngineConfig()))
                .isNotEqualTo(service.configFingerprint(first, new MatchEngineConfig()));
    }

    @Test
    void inputFingerprintUsesCanonicalLineupOrderAndConsumedPlayerContext() {
        List<CanonicalLineupPlayer> players = players(1);
        CanonicalRuntimeTeamInput first = team(players);
        List<CanonicalLineupPlayer> reversed = new ArrayList<>(players);
        java.util.Collections.reverse(reversed);
        CanonicalRuntimeTeamInput second = team(reversed);
        CanonicalRuntimeScoringService.RuntimeScoringRequest request =
                CanonicalRuntimeScoringService.RuntimeScoringRequest.home(
                        "CTIM:1", 7, 2026, 1, 1, 2,
                        new PersonalizedTactic(), new PersonalizedTactic(), List.of(), List.of());

        assertThat(service.inputFingerprint(request, first, first))
                .isEqualTo(service.inputFingerprint(request, second, second));

        List<CanonicalLineupPlayer> changed = new ArrayList<>(players);
        Map<PlayerAttribute, Integer> attributes = new EnumMap<>(changed.get(0).attributes());
        attributes.put(PlayerAttribute.PASSING, 19);
        CanonicalLineupPlayer changedPlayer = new CanonicalLineupPlayer(changed.get(0).playerId(),
                changed.get(0).usedPosition(), changed.get(0).occurrence(), changed.get(0).role(),
                changed.get(0).duty(), attributes, changed.get(0).fitness(), changed.get(0).morale(),
                changed.get(0).capability(), changed.get(0).roleSuitability(), changed.get(0).traits(),
                changed.get(0).forwardInstruction());
        changed.set(0, changedPlayer);
        assertThat(service.inputFingerprint(request, team(changed), team(changed)))
                .isNotEqualTo(service.inputFingerprint(request, first, first));
    }

    @Test
    void engineFingerprintsContainConsumedInputsAndIgnoreIrrelevantTacticFields() {
        MatchEngineConfig first = new MatchEngineConfig();
        MatchEngineConfig second = new MatchEngineConfig();
        assertThat(service.fallbackConfigFingerprint(first, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.SCALAR_FALLBACK))
                .isEqualTo(service.fallbackConfigFingerprint(second, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.SCALAR_FALLBACK));
        second.getPower().setExpectedGoalsTotal(4.0);
        assertThat(service.fallbackConfigFingerprint(first, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.SCALAR_FALLBACK))
                .isNotEqualTo(service.fallbackConfigFingerprint(second, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.SCALAR_FALLBACK));

        second = new MatchEngineConfig();
        second.getTacticalModel().getAttackShare().put("ST", 0.9);
        assertThat(service.fallbackConfigFingerprint(first, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK))
                .isNotEqualTo(service.fallbackConfigFingerprint(second, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK));
        second = new MatchEngineConfig();
        second.getPlayerValue().setFitnessFloor(0.8);
        assertThat(service.fallbackConfigFingerprint(first, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK))
                .isNotEqualTo(service.fallbackConfigFingerprint(second, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK));

        String profileA = service.fallbackInputFingerprint("CTIM:1", 1, 2, 100, 90,
                108, 90, new TacticalScoreService.TeamProfile(60, 40, 1.1, 0.9, 1.0),
                new TacticalScoreService.TeamProfile(50, 40, 1.0, 1.0, 1.0),
                new TacticalScoreService.TacticVector(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7),
                new TacticalScoreService.TacticVector(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6),
                1.02, 0.98, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK);
        String profileB = service.fallbackInputFingerprint("CTIM:1", 1, 2, 100, 90,
                108, 90, new TacticalScoreService.TeamProfile(55, 45, 1.1, 0.9, 1.0),
                new TacticalScoreService.TeamProfile(50, 40, 1.0, 1.0, 1.0),
                new TacticalScoreService.TacticVector(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7),
                new TacticalScoreService.TacticVector(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6),
                1.02, 0.98, com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK);
        assertThat(profileA).isNotEqualTo(profileB);

        PersonalizedTactic irrelevantA = new PersonalizedTactic();
        PersonalizedTactic irrelevantB = new PersonalizedTactic();
        irrelevantA.setPenaltyTakerId(1L);
        irrelevantB.setPenaltyTakerId(99L);
        CanonicalRuntimeScoringService.RuntimeScoringRequest requestA =
                CanonicalRuntimeScoringService.RuntimeScoringRequest.home("CTIM:1", 7, 2026, 1, 1, 2,
                        irrelevantA, new PersonalizedTactic(), List.of(), List.of());
        CanonicalRuntimeScoringService.RuntimeScoringRequest requestB =
                CanonicalRuntimeScoringService.RuntimeScoringRequest.home("CTIM:1", 7, 2026, 1, 1, 2,
                        irrelevantB, new PersonalizedTactic(), List.of(), List.of());
        CanonicalRuntimeTeamInput canonical = team(players(1));
        assertThat(service.inputFingerprint(requestA, canonical, canonical))
                .isEqualTo(service.inputFingerprint(requestB, canonical, canonical));
    }

    @Test
    void adminFingerprintIsVersionedAndSeparatesConfigFromFixtureScore() {
        assertThat(service.adminOverrideConfigFingerprint())
                .matches("[0-9a-f]{64}");
        assertThat(service.adminOverrideConfigFingerprint())
                .isNotEqualTo(service.adminOverrideInputFingerprint("CTIM:1", 1, 0));
        assertThat(service.adminOverrideInputFingerprint("CTIM:1", 1, 0))
                .isNotEqualTo(service.adminOverrideInputFingerprint("CTIM:1", 2, 0));
    }

    @Test
    void roleAndInstructionWeightsAreScopedToTheirEngines() {
        CompartmentEngineConfig compartment = new CompartmentEngineConfig();
        MatchEngineConfig first = new MatchEngineConfig();
        MatchEngineConfig second = new MatchEngineConfig();

        second.getRoleWeights().setSuitabilityScale(6.0);
        assertThat(service.configFingerprint(compartment, first))
                .isNotEqualTo(service.configFingerprint(compartment, second));

        second = new MatchEngineConfig();
        second.getRoleWeights().setAttributes(Map.of("Poacher", Map.of("Finishing", 2.0)));
        assertThat(service.configFingerprint(compartment, first))
                .isNotEqualTo(service.configFingerprint(compartment, second));

        second = new MatchEngineConfig();
        second.getInstructionWeights().setBonusScale(1.2);
        assertThat(service.fallbackConfigFingerprint(first,
                com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK))
                .isNotEqualTo(service.fallbackConfigFingerprint(second,
                        com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK));
        second = new MatchEngineConfig();
        second.getInstructionWeights().setConflictPenalty(0.03);
        assertThat(service.fallbackConfigFingerprint(first,
                com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK))
                .isNotEqualTo(service.fallbackConfigFingerprint(second,
                        com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK));
        second = new MatchEngineConfig();
        second.getInstructionWeights().setClampMin(0.9);
        assertThat(service.fallbackConfigFingerprint(first,
                com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK))
                .isNotEqualTo(service.fallbackConfigFingerprint(second,
                        com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK));
        second = new MatchEngineConfig();
        MatchEngineConfig.InstructionWeights.InstructionBonus bonus =
                new MatchEngineConfig.InstructionWeights.InstructionBonus();
        bonus.setBase(0.05);
        second.getInstructionWeights().getBonuses().put("Shoot More Often", bonus);
        assertThat(service.fallbackConfigFingerprint(first,
                com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK))
                .isNotEqualTo(service.fallbackConfigFingerprint(second,
                        com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK));
        assertThat(service.fallbackConfigFingerprint(first,
                com.footballmanagergamesimulator.matchplan.ScoreEngineKind.SCALAR_FALLBACK))
                .isEqualTo(service.fallbackConfigFingerprint(second,
                        com.footballmanagergamesimulator.matchplan.ScoreEngineKind.SCALAR_FALLBACK));
        assertThat(service.configFingerprint(compartment, first))
                .isEqualTo(service.configFingerprint(compartment, second));

        MatchEngineConfig orderedA = new MatchEngineConfig();
        MatchEngineConfig orderedB = new MatchEngineConfig();
        orderedA.getInstructionWeights().setConflicts(List.of(
                new MatchEngineConfig.InstructionWeights.ConflictPair("A", "B"),
                new MatchEngineConfig.InstructionWeights.ConflictPair("C", "D")));
        orderedB.getInstructionWeights().setConflicts(List.of(
                new MatchEngineConfig.InstructionWeights.ConflictPair("D", "C"),
                new MatchEngineConfig.InstructionWeights.ConflictPair("B", "A")));
        assertThat(service.fallbackConfigFingerprint(orderedA,
                com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK))
                .isEqualTo(service.fallbackConfigFingerprint(orderedB,
                        com.footballmanagergamesimulator.matchplan.ScoreEngineKind.TWO_AXIS_FALLBACK));
    }

    private static CompartmentEngineConfig.CompartmentWeights weights(PlayerAttribute attribute, double value) {
        CompartmentEngineConfig.CompartmentWeights weights = new CompartmentEngineConfig.CompartmentWeights();
        weights.getAttributes().put(attribute, value);
        return weights;
    }

    private static CanonicalRuntimeTeamInput team(List<CanonicalLineupPlayer> players) {
        Map<Long, TacticalContextInput> contexts = new LinkedHashMap<>();
        players.forEach(player -> contexts.put(player.playerId(), TacticalContextInput.neutral()));
        return new CanonicalRuntimeTeamInput(Mentality.BALANCED, players, contexts);
    }

    private static List<CanonicalLineupPlayer> players(long offset) {
        PlayerPosition[] positions = {PlayerPosition.GK, PlayerPosition.DC, PlayerPosition.DL,
                PlayerPosition.DR, PlayerPosition.DM, PlayerPosition.MC, PlayerPosition.ML,
                PlayerPosition.MR, PlayerPosition.AMC, PlayerPosition.AML, PlayerPosition.ST};
        List<CanonicalLineupPlayer> result = new ArrayList<>();
        for (int index = 0; index < positions.length; index++) {
            long id = offset + index;
            EnumMap<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
            for (PlayerAttribute attribute : PlayerAttribute.values()) attributes.put(attribute, 10);
            result.add(new CanonicalLineupPlayer(id, positions[index], 1, null, Duty.SUPPORT,
                    attributes, 90, 70,
                    new PlayerCapabilitySnapshot(id, positions[index], Map.of(positions[index], 20),
                            Map.of(), 8, 20, false, false, false), 50,
                    java.util.Set.of(), ForwardInstruction.DEFAULT));
        }
        return result;
    }
}
