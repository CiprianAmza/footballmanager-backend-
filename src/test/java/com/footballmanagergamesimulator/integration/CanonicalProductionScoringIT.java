package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
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

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that a real league round scores end-to-end through authoritative Compartment V1. */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Compartment V1 — production round scores end-to-end")
class CanonicalProductionScoringIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private CompetitionTeamInfoDetailRepository detailRepository;
    @Autowired private CompartmentEngineConfig compartmentConfig;

    @Test
    void productionRoundScoresViaCanonicalEngine() {
        Competition league = competitionRepository.findAll().stream()
                .filter(Competition::isTopFlight)
                .findFirst()
                .orElseThrow();
        int fixtures = matchRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                league.getId(), 1L, "1").size();
        assertThat(fixtures).isGreaterThan(0);

        competitionController.simulateRound(String.valueOf(league.getId()), "1");

        List<CompetitionTeamInfoDetail> details = detailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(league.getId(), 1L, 1L);
        assertThat(details).hasSize(fixtures);
        int cap = compartmentConfig.getProbability().getGoalCap();
        for (CompetitionTeamInfoDetail detail : details) {
            assertThat(detail.getScore()).isNotNull();
            String[] parts = detail.getScore().split("-");
            assertThat(parts).hasSize(2);
            assertThat(Integer.parseInt(parts[0].trim())).isBetween(0, cap);
            assertThat(Integer.parseInt(parts[1].trim())).isBetween(0, cap);
        }
    }
}
