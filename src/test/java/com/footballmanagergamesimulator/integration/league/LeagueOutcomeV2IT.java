package com.footballmanagergamesimulator.integration.league;

import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoringFingerprintService;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-path league outcome proof for the canonical Compartment V1 scorer.
 *
 * <p>Unlike {@code LeagueOutcomeIT}, this test does not call the scalar
 * {@code TournamentEngine} abstraction. It enters through the real production
 * round simulator, then verifies the persisted scoring decision, xG and
 * configuration fingerprint for every fixture.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.compartment.enabled=true",
        "match.engine.match-plan.enabled=true",
        "bootstrap.seed=20260528"
})
@DisplayName("League outcome V2 — canonical Compartment V1 production scorer")
class LeagueOutcomeV2IT {

    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private MatchPlanRepository matchPlanRepository;
    @Autowired private CompartmentEngineConfig compartmentConfig;
    @Autowired private MatchEngineConfig matchEngineConfig;
    @Autowired private CanonicalScoringFingerprintService fingerprintService;

    @Test
    @DisplayName("league round persists Compartment V1 decisions with current weights and xG")
    void leagueRoundUsesCanonicalProductionScorer() {
        Competition league = firstLeague();
        List<CompetitionTeamInfoMatch> fixtures = matchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(league.getId(), 1L, "1");
        assertThat(fixtures).as("round-1 fixtures should exist").isNotEmpty();
        assertThat(compartmentConfig.isEnabled()).isTrue();

        competitionController.simulateRound(String.valueOf(league.getId()), "1");

        String expectedConfigFingerprint = fingerprintService
                .configFingerprint(compartmentConfig, matchEngineConfig);
        int goalCap = compartmentConfig.getProbability().getGoalCap();

        for (CompetitionTeamInfoMatch fixture : fixtures) {
            String fixtureKey = MatchPlanService.competitionFixtureKey(fixture.getId());
            MatchPlan plan = matchPlanRepository.findByFixtureKey(fixtureKey).orElseThrow(
                    () -> new AssertionError("missing MatchPlan for " + fixtureKey));

            assertThat(plan.getStatus()).isEqualTo(MatchPlan.Status.COMMITTED);
            assertThat(plan.getScoreEngine()).isEqualTo(ScoreEngineKind.COMPARTMENT_V1);
            assertThat(plan.getScoreAlgorithmVersion())
                    .isEqualTo(ScoreEngineKind.COMPARTMENT_V1.algorithmVersion());
            assertThat(plan.getScoreConfigFingerprint())
                    .isEqualTo(expectedConfigFingerprint);
            assertThat(plan.getScoreInputFingerprint()).matches("[0-9a-f]{64}");
            assertThat(plan.getHomeXg()).isNotNull().isGreaterThanOrEqualTo(0.0);
            assertThat(plan.getAwayXg()).isNotNull().isGreaterThanOrEqualTo(0.0);
            assertThat(plan.getHomeScore90()).isBetween(0, goalCap);
            assertThat(plan.getAwayScore90()).isBetween(0, goalCap);
        }
    }

    private Competition firstLeague() {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1)
                .min((a, b) -> Long.compare(a.getId(), b.getId()))
                .orElseThrow(() -> new IllegalStateException("no league competition in bootstrap world"));
    }
}
