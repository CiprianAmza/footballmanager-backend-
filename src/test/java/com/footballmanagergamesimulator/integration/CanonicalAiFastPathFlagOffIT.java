package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flag-OFF regression: with {@code match.engine.match-plan.enabled=false} the AI-vs-AI
 * fast-path must stay on the legacy {@code getScorersForTeamSimplified} distribution and
 * write NO canonical artifacts — no MatchPlan, no MatchEvent — while still producing the
 * legacy Scorer rows. This is the guarantee that the new path is fully behind the flag.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.match-plan.enabled=false",
        "gameplay.player-availability-disabled=false"
})
@DisplayName("Canonical AI fast-path — flag OFF (legacy unchanged)")
class CanonicalAiFastPathFlagOffIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private MatchPlanRepository matchPlanRepository;
    @Autowired private MatchEventRepository matchEventRepository;
    @Autowired private ScorerRepository scorerRepository;

    private Competition firstLeague() {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1)
                .min((a, b) -> Long.compare(a.getId(), b.getId()))
                .orElseThrow(() -> new IllegalStateException("no league competition in bootstrap world"));
    }

    @Test
    @DisplayName("Legacy AI round writes Scorer rows but no canonical plan/events")
    void legacyAiRound_writesNoCanonicalArtifacts() {
        Competition league = firstLeague();
        int round = 1;
        List<CompetitionTeamInfoMatch> fixtures = matchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(league.getId(), (long) round, "1");
        assertFalse(fixtures.isEmpty(), "precondition: round-1 fixtures exist");

        competitionController.simulateRound(String.valueOf(league.getId()), String.valueOf(round));

        // No canonical plan or event was written for any fixture.
        for (CompetitionTeamInfoMatch fixture : fixtures) {
            String key = MatchPlanService.competitionFixtureKey(fixture.getId());
            assertTrue(matchPlanRepository.findByFixtureKey(key).isEmpty(),
                    "flag OFF must not create a MatchPlan for " + key);
            assertTrue(matchEventRepository.findByFixtureKey(key).isEmpty(),
                    "flag OFF must not create MatchEvents for " + key);
        }
        assertEquals(0, matchPlanRepository.count(), "no canonical plans exist with the flag off");
        assertEquals(0, matchEventRepository.findAllByCompetitionIdAndSeasonNumberAndRoundNumber(
                        league.getId(), 1, round).size(),
                "no canonical events exist with the flag off");

        // The legacy distribution still produced Scorer rows (appearances + goals).
        assertFalse(scorerRepository.findAllByCompetitionIdAndSeasonNumber(league.getId(), 1).isEmpty(),
                "legacy simplified path must still produce Scorer rows");
    }
}
