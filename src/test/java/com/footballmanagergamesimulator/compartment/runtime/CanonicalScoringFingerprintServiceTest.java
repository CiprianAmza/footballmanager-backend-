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
    void configFingerprintIsOrderIndependentAndTracksCanonicalWeights() {
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
        assertThat(service.configFingerprint(first, new MatchEngineConfig()))
                .isEqualTo(service.configFingerprint(second, new MatchEngineConfig()));

        second.getRating().setScoreScale(101.0);
        assertThat(service.configFingerprint(second, new MatchEngineConfig()))
                .isNotEqualTo(service.configFingerprint(first, new MatchEngineConfig()));

        second = new CompartmentEngineConfig();
        second.getShooter().getPressing().get("VeryEasy").setRedCardChance(0.01);
        assertThat(service.configFingerprint(second, new MatchEngineConfig()))
                .isNotEqualTo(service.configFingerprint(new CompartmentEngineConfig(), new MatchEngineConfig()));
    }

    @Test
    void inputFingerprintUsesCanonicalLineupOrderAndConsumedPlayerContext() {
        List<CanonicalLineupPlayer> players = players(1);
        CanonicalRuntimeTeamInput first = team(players);
        List<CanonicalLineupPlayer> reversed = new ArrayList<>(players);
        java.util.Collections.reverse(reversed);
        CanonicalRuntimeTeamInput second = team(reversed);
        var request = request(new PersonalizedTactic());

        assertThat(service.inputFingerprint(request, first, first))
                .isEqualTo(service.inputFingerprint(request, second, second));

        List<CanonicalLineupPlayer> changed = new ArrayList<>(players);
        CanonicalLineupPlayer original = changed.get(0);
        Map<PlayerAttribute, Integer> attributes = new EnumMap<>(original.attributes());
        attributes.put(PlayerAttribute.PASSING, 19);
        changed.set(0, new CanonicalLineupPlayer(original.playerId(), original.usedPosition(),
                original.occurrence(), original.role(), original.duty(), attributes, original.fitness(),
                original.morale(), original.capability(), original.roleSuitability(), original.traits(),
                original.forwardInstruction()));
        assertThat(service.inputFingerprint(request, team(changed), team(changed)))
                .isNotEqualTo(service.inputFingerprint(request, first, first));
    }

    @Test
    void canonicalInputFingerprintIgnoresUnconsumedTacticFields() {
        PersonalizedTactic first = new PersonalizedTactic();
        PersonalizedTactic second = new PersonalizedTactic();
        first.setPenaltyTakerId(1L);
        second.setPenaltyTakerId(99L);
        CanonicalRuntimeTeamInput canonical = team(players(1));
        assertThat(service.inputFingerprint(request(first), canonical, canonical))
                .isEqualTo(service.inputFingerprint(request(second), canonical, canonical));
    }

    @Test
    void adminFingerprintIsVersionedAndSeparatesConfigFromFixtureScore() {
        assertThat(service.adminOverrideConfigFingerprint()).matches("[0-9a-f]{64}");
        assertThat(service.adminOverrideConfigFingerprint())
                .isNotEqualTo(service.adminOverrideInputFingerprint("CTIM:1", 1, 0));
        assertThat(service.adminOverrideInputFingerprint("CTIM:1", 1, 0))
                .isNotEqualTo(service.adminOverrideInputFingerprint("CTIM:1", 2, 0));
    }

    @Test
    void fingerprintContainsCanonicalRoleSuitabilityButNotRetiredEngineWeights() {
        CompartmentEngineConfig compartment = new CompartmentEngineConfig();
        MatchEngineConfig first = new MatchEngineConfig();
        MatchEngineConfig second = new MatchEngineConfig();
        second.getRoleWeights().setSuitabilityScale(6.0);
        assertThat(service.configFingerprint(compartment, first))
                .isNotEqualTo(service.configFingerprint(compartment, second));

        second = new MatchEngineConfig();
        second.getInstructionWeights().setBonusScale(1.2);
        assertThat(service.configFingerprint(compartment, first))
                .isEqualTo(service.configFingerprint(compartment, second));
    }

    private static CanonicalRuntimeScoringService.RuntimeScoringRequest request(PersonalizedTactic tactic) {
        return CanonicalRuntimeScoringService.RuntimeScoringRequest.home(
                "CTIM:1", 7, 2026, 1, 1, 2,
                tactic, new PersonalizedTactic(), List.of(), List.of());
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
