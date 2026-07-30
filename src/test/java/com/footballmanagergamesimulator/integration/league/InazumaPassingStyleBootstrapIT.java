package com.footballmanagergamesimulator.integration.league;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateRepository;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import com.footballmanagergamesimulator.service.MatchRoundSimulator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
class InazumaPassingStyleBootstrapIT {
    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private PersonalizedTacticRepository tacticRepository;
    @Autowired private TacticSimulationService tacticSimulationService;
    @Autowired private MatchRoundSimulator matchRoundSimulator;
    @Autowired private ChairmanTacticalMandateRepository mandateRepository;

    @Test
    void inazumaHasTheExactNamedPassingStyleEleven() throws Exception {
        Team team = teamRepository.findAll().stream()
                .filter(candidate -> "Inazuma Japan".equals(candidate.getName()))
                .findFirst().orElseThrow();
        Map<String, Human> players = humanRepository.findAllByTeamId(team.getId()).stream()
                .collect(Collectors.toMap(Human::getName, Function.identity(), (first, duplicate) -> first));

        assertPlayer(players, "Saviola", "AMC", 300, 20);
        assertPlayer(players, "Umbreon", "ML", 260, 20);
        assertPlayer(players, "Itexoa", "MC", 280, 20);
        assertPlayer(players, "Ixianus", "MR", 240, 19);
        assertPlayer(players, "Raijin", "DM", 260, 19);
        assertPlayer(players, "Kaminari", "MC", 260, 19);
        PlayerSkills saviolaSkills = playerSkillsRepository
                .findPlayerSkillsByPlayerId(players.get("Saviola").getId()).orElseThrow();
        assertThat(saviolaSkills.getPassing()).isEqualTo(20);

        PersonalizedTactic tactic = tacticRepository.findPersonalizedTacticByTeamId(team.getId()).orElseThrow();
        assertThat(tactic.getTactic()).isEqualTo("31411");
        assertThat(tactic.getMentality()).isEqualTo("Balanced");
        assertThat(tactic.getTempo()).isEqualTo("Standard");
        assertThat(tactic.getPassingType()).isEqualTo("Short");
        assertThat(tactic.getPressing()).isEqualTo("Aggressive");
        assertThat(tactic.getRecovery()).isEqualTo("Instantly");

        List<FormationData> saved = new ObjectMapper().readValue(
                tactic.getFirst11(), new TypeReference<List<FormationData>>() {});
        Map<Integer, String> starters = saved.stream()
                .filter(row -> row.getPositionIndex() < 30)
                .collect(Collectors.toMap(FormationData::getPositionIndex,
                        row -> players.values().stream()
                                .filter(player -> player.getId() == row.getPlayerId())
                                .map(Human::getName).findFirst().orElseThrow()));
        assertThat(starters).containsEntry(7, "Saviola")
                .containsEntry(10, "Umbreon")
                .containsEntry(11, "Itexoa")
                .containsEntry(13, "Kaminari")
                .containsEntry(14, "Ixianus")
                .containsEntry(17, "Raijin")
                .hasSize(11);
        assertThat(saved.stream().filter(row -> row.getPlayerId() == players.get("Saviola").getId())
                .findFirst().orElseThrow().isShadow()).isTrue();

        var mandate = mandateRepository.findByTeamId(team.getId()).orElseThrow();
        assertThat(mandate.getRequiredFormation()).isEqualTo("31411");
        Map<Integer, Long> mandatedPlayers = mandate.getSlots().stream().collect(Collectors.toMap(
                slot -> slot.getPositionIndex(), slot -> slot.getRequiredPlayerId()));
        assertThat(mandatedPlayers)
                .containsEntry(7, players.get("Saviola").getId())
                .containsEntry(10, players.get("Umbreon").getId())
                .containsEntry(11, players.get("Itexoa").getId())
                .containsEntry(13, players.get("Kaminari").getId())
                .containsEntry(14, players.get("Ixianus").getId())
                .containsEntry(17, players.get("Raijin").getId())
                .hasSize(6);

        var canonical = tacticSimulationService.canonicalFormation(team.getId(), "31411", tactic);
        assertThat(canonical.input().lineup()).extracting(slot -> slot.playerId())
                .contains(players.get("Saviola").getId(), players.get("Umbreon").getId(),
                        players.get("Itexoa").getId(), players.get("Ixianus").getId(),
                        players.get("Raijin").getId(), players.get("Kaminari").getId());
        assertThat(canonical.evaluation().team().passingStyle().active()).isTrue();
        assertThat(canonical.evaluation().team().passingStyle().midfieldAverage()).isEqualTo(19.5);
        assertThat(canonical.evaluation().team().passingStyle().midfielders()).hasSize(6);

        var aiStarters = matchRoundSimulator.aiStarterSlotsForTest(team.getId());
        assertThat(aiStarters).hasSize(11);
        Map<String, String> aiPositions = aiStarters.stream().collect(Collectors.toMap(
                slot -> slot.player().getName(), slot -> slot.usedPosition()));
        assertThat(aiPositions)
                .containsEntry("Saviola", "AMC")
                .containsEntry("Umbreon", "ML")
                .containsEntry("Itexoa", "MC")
                .containsEntry("Kaminari", "MC")
                .containsEntry("Ixianus", "MR")
                .containsEntry("Raijin", "DM");
    }

    private void assertPlayer(Map<String, Human> players, String name, String position,
                              double rating, int passingAttribute) {
        Human player = players.get(name);
        assertThat(player).as(name).isNotNull();
        assertThat(player.getPosition()).isEqualTo(position);
        assertThat(player.getRating()).isEqualTo(rating);
        assertThat(player.isWillNeverLeave()).isTrue();
        PlayerSkills skills = playerSkillsRepository.findPlayerSkillsByPlayerId(player.getId()).orElseThrow();
        assertThat(skills.getBallRecovery()).isEqualTo(passingAttribute);
        assertThat(skills.getTackling()).isEqualTo(passingAttribute);
        assertThat(skills.getPace()).isEqualTo(passingAttribute);
    }
}
