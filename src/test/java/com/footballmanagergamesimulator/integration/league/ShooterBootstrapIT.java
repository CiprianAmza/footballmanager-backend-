package com.footballmanagergamesimulator.integration.league;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateRepository;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import com.footballmanagergamesimulator.service.PlayerCardService;
import com.footballmanagergamesimulator.service.MatchRoundSimulator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShooterBootstrapIT {
    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private PersonalizedTacticRepository tacticRepository;
    @Autowired private TacticSimulationService tacticSimulationService;
    @Autowired private PlayerCardService playerCardService;
    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private MatchEventRepository matchEventRepository;
    @Autowired private ScorerRepository scorerRepository;
    @Autowired private ChairmanTacticalMandateRepository mandateRepository;
    @Autowired private MatchRoundSimulator matchRoundSimulator;

    @Test
    @Order(1)
    void laurentiuBudIsAthleticSohatusStartingMlAndExplicitShooter() throws Exception {
        Team athletic = teamRepository.findAll().stream()
                .filter(team -> "Athletic Sohatu".equals(team.getName()))
                .findFirst().orElseThrow();
        Human laurentiu = humanRepository.findAllByTeamId(athletic.getId()).stream()
                .filter(player -> "Laurentiu Bud".equals(player.getName()))
                .findFirst().orElseThrow();
        PlayerSkills skills = playerSkillsRepository.findPlayerSkillsByPlayerId(laurentiu.getId()).orElseThrow();
        PersonalizedTactic tactic = tacticRepository.findPersonalizedTacticByTeamId(athletic.getId()).orElseThrow();
        List<FormationData> saved = new ObjectMapper().readValue(
                tactic.getFirst11(), new TypeReference<List<FormationData>>() {});
        FormationData savedShooter = saved.stream()
                .filter(row -> row.getPlayerId() == laurentiu.getId()).findFirst().orElseThrow();

        assertThat(laurentiu.getPosition()).isEqualTo("ML");
        assertThat(laurentiu.getRating()).isEqualTo(100.0);
        assertThat(skills.getLongShots()).isEqualTo(20);
        assertThat(skills.getPositioning()).isEqualTo(20);
        assertThat(tactic.getTactic()).isEqualTo("442");
        assertThat(savedShooter.getPositionIndex()).isEqualTo(10);
        assertThat(savedShooter.getSpecialRole()).isEqualTo("SHOOTER");
        var mandate = mandateRepository.findByTeamId(athletic.getId()).orElseThrow();
        assertThat(mandate.getRequiredFormation()).isEqualTo("442");
        assertThat(mandate.getSlots()).singleElement().satisfies(slot -> {
            assertThat(slot.getPositionIndex()).isEqualTo(10);
            assertThat(slot.getRequiredPlayerId()).isEqualTo(laurentiu.getId());
        });
        assertThat(matchRoundSimulator.aiStarterSlotsForTest(athletic.getId()))
                .filteredOn(slot -> slot.player().getId() == laurentiu.getId())
                .singleElement()
                .extracting(slot -> slot.usedPosition())
                .isEqualTo("ML");

        var formation = tacticSimulationService.canonicalFormation(
                athletic.getId(), tactic.getTactic(), tactic);
        var canonicalShooter = formation.input().lineup().stream()
                .filter(player -> player.traits().contains(PlayerTrait.SHOOTER))
                .findFirst().orElseThrow();
        assertThat(canonicalShooter.playerId()).isEqualTo(laurentiu.getId());
        assertThat(canonicalShooter.usedPosition()).isEqualTo(PlayerPosition.ML);
        assertThat(formation.evaluation().team().shooter().playerId()).isEqualTo(laurentiu.getId());
        assertThat(formation.evaluation().team().shooter().longShots()).isEqualTo(20);
        assertThat(formation.evaluation().team().shooter().positioning()).isEqualTo(20);

        // The player page consumes this card. Spring canonicalizes YAML map keys such as
        // "Long Shots" to "LongShots", so exercise the real bound configuration here.
        assertThat(playerCardService.getPlayerCard(laurentiu.getId())).isPresent();
    }

    @Test
    @Order(2)
    void laurentiuBudReceivesHisSpecialGoalsEndToEndAcrossTheLeagueSeason() {
        Team athletic = teamRepository.findAll().stream()
                .filter(team -> "Athletic Sohatu".equals(team.getName()))
                .findFirst().orElseThrow();
        Human laurentiu = humanRepository.findAllByTeamId(athletic.getId()).stream()
                .filter(player -> "Laurentiu Bud".equals(player.getName()))
                .findFirst().orElseThrow();
        long leagueId = competitionRepository.findById(athletic.getCompetitionId()).orElseThrow().getId();

        List<Long> rounds = matchRepository.findDistinctRoundsByCompetitionIdAndSeasonNumber(leagueId, "1");
        rounds.stream().sorted().forEach(round ->
                competitionController.simulateRound(String.valueOf(leagueId), String.valueOf(round)));

        List<MatchEvent> allEvents = matchEventRepository.findAll();
        List<MatchEvent> shooterGoalEvents = allEvents.stream()
                .filter(event -> event.getPlayerId() == laurentiu.getId())
                .filter(event -> "goal".equals(event.getEventType()))
                .filter(event -> "SHOOTER".equals(event.getDetails()))
                .toList();
        long shooterEvents = shooterGoalEvents.size();
        int scorerGoals = scorerRepository.findAllByPlayerId(laurentiu.getId()).stream()
                .filter(row -> row.getCompetitionId() == leagueId && row.getSeasonNumber() == 1)
                .mapToInt(com.footballmanagergamesimulator.model.Scorer::getGoals)
                .sum();

        assertThat(shooterEvents).as("Bud's persisted SHOOTER goal events").isPositive();
        assertThat(shooterGoalEvents).allSatisfy(goal -> {
            List<MatchEvent> assistsForGoal = allEvents.stream()
                    .filter(event -> "assist".equals(event.getEventType()))
                    .filter(event -> event.getFixtureKey().equals(goal.getFixtureKey()))
                    .filter(event -> event.getSlotIndex() == goal.getSlotIndex())
                    .toList();
            assertThat(assistsForGoal).as("one assist for SHOOTER goal slot " + goal.getSlotIndex())
                    .hasSize(1);
            assertThat(assistsForGoal.get(0).getPlayerId()).isNotEqualTo(laurentiu.getId());
        });
        assertThat(scorerGoals).as("Bud's projected season goals").isGreaterThanOrEqualTo((int) shooterEvents);
    }
}
