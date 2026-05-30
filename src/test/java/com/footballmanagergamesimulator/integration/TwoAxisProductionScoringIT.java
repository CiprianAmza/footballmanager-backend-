package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the two-axis tactical model wired into production scoring (behind
 * {@code match.engine.tactical-model.enabled=true}): a real league round simulates end-to-end
 * through {@code CompetitionController.simulateRound} and produces valid scorelines. The rest of
 * the suite runs with the flag OFF (the default), so this is the only place the new production
 * path is exercised — the scalar engine's tuned baselines are untouched.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.tactical-model.enabled=true",
        "bootstrap.seed=20260528"
})
@DisplayName("Two-axis model — production round scores end-to-end when enabled")
class TwoAxisProductionScoringIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private CompetitionTeamInfoDetailRepository detailRepository;
    @Autowired private MatchEngineConfig engineConfig;

    @Test
    void productionRoundScoresViaTwoAxisModel() {
        assertThat(engineConfig.getTacticalModel().isEnabled())
                .as("the flag must be ON in this context").isTrue();

        Competition league = firstLeague();
        int fixtures = matchRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                league.getId(), 1L, "1").size();
        assertThat(fixtures).as("round-1 fixtures should exist").isGreaterThan(0);

        competitionController.simulateRound(String.valueOf(league.getId()), "1");

        List<CompetitionTeamInfoDetail> details = detailRepository.findAllByCompetitionIdAndRoundIdAndSeasonNumber(
                league.getId(), 1L, 1L);
        assertThat(details).as("every fixture produces a detail row").hasSize(fixtures);

        int cap = engineConfig.getTacticalModel().getMaxGoalsPerTeam();
        for (CompetitionTeamInfoDetail d : details) {
            assertThat(d.getScore()).as("detail row should have a score").isNotNull();
            int[] s = parseScore(d.getScore());
            assertThat(s[0]).as("home goals in [0,cap]").isBetween(0, cap);
            assertThat(s[1]).as("away goals in [0,cap]").isBetween(0, cap);
        }
    }

    private Competition firstLeague() {
        List<Competition> leagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1)
                .collect(Collectors.toList());
        assertThat(leagues).as("bootstrap should have created a league").isNotEmpty();
        return leagues.get(0);
    }

    private int[] parseScore(String score) {
        String[] parts = score.split("-");
        assertThat(parts).as("score 'X-Y', got " + score).hasSize(2);
        return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
    }
}
