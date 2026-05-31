package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import com.footballmanagergamesimulator.service.TacticSimulationService.CompetitionResult;
import com.footballmanagergamesimulator.service.TacticSimulationService.StandingRow;
import com.footballmanagergamesimulator.service.TacticSimulationService.TacticPointsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the production {@link TacticSimulationService}: deterministic season-points ranking
 * (default-league and custom-opponents), and a coherent round-robin standings table. Uses the
 * bootstrapped DB and a fixed seed inside the service, so results are reproducible.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.tactical-model.enabled=true",
        "bootstrap.seed=20260528"
})
@DisplayName("TacticSimulationService — simulated tactic points + custom competition")
class TacticSimulationServiceIT {

    @Autowired private TacticSimulationService tacticSimulationService;
    @Autowired private GameStateService gameState;
    @Autowired private CompetitionTeamInfoRepository ctiRepo;

    @Test
    void simulateTacticPoints_defaultLeagueOpponents_ranksAll900Deterministically() {
        List<Long> league = aLeague();
        long teamId = league.get(0);

        TacticPointsResult r1 = tacticSimulationService.simulateTacticPoints(teamId, "442", 2, null);
        TacticPointsResult r2 = tacticSimulationService.simulateTacticPoints(teamId, "442", 2, null);

        assertThat(r1.rows()).hasSize(900);
        assertThat(r1.formation()).isEqualTo("442");
        assertThat(r1.seasons()).isEqualTo(2);
        assertThat(r1.opponentCount()).isEqualTo(league.size() - 1);

        // Sorted by avgPoints descending.
        for (int i = 1; i < r1.rows().size(); i++) {
            assertThat(r1.rows().get(i - 1).avgPoints())
                    .isGreaterThanOrEqualTo(r1.rows().get(i).avgPoints());
        }
        // min <= avg <= max, and points within [0, maxPossible].
        int maxPossible = (league.size() - 1) * 2 * 3;
        for (var row : r1.rows()) {
            assertThat(row.minPoints()).isLessThanOrEqualTo(row.maxPoints());
            assertThat(row.avgPoints()).isBetween((double) row.minPoints(), (double) row.maxPoints());
            assertThat(row.maxPoints()).isBetween(0, maxPossible);
        }
        // Deterministic: same seed => identical top row.
        assertThat(r2.rows().get(0).avgPoints()).isEqualTo(r1.rows().get(0).avgPoints());
        assertThat(r2.rows().get(0).mentality()).isEqualTo(r1.rows().get(0).mentality());
    }

    @Test
    void simulateTacticPoints_customOpponentIds_usesOnlyThoseOpponents() {
        List<Long> league = aLeague();
        long teamId = league.get(0);
        List<Long> opponents = league.subList(1, Math.min(4, league.size()));

        TacticPointsResult r = tacticSimulationService.simulateTacticPoints(teamId, "433", 1, opponents);

        assertThat(r.rows()).hasSize(900);
        assertThat(r.formation()).isEqualTo("433");
        assertThat(r.opponentCount()).isEqualTo(opponents.size());
        int maxPossible = opponents.size() * 2 * 3;
        for (var row : r.rows()) {
            assertThat(row.maxPoints()).isBetween(0, maxPossible);
        }
    }

    @Test
    void simulateCompetition_returnsCoherentStandings() {
        List<Long> league = aLeague();
        List<Long> teams = new ArrayList<>(league.subList(0, Math.min(4, league.size())));
        int n = teams.size();
        int seasons = 2;

        CompetitionResult result = tacticSimulationService.simulateCompetition(teams, seasons);
        List<StandingRow> standings = result.standings();

        assertThat(standings).hasSize(n);
        // Each team plays (n-1) home + (n-1) away per season => 2*(n-1) per season.
        int expectedPlayedPerTeam = 2 * (n - 1) * seasons;
        int totalGoalsFor = 0, totalGoalsAgainst = 0, totalPlayed = 0;
        for (StandingRow row : standings) {
            assertThat(row.played()).isEqualTo(expectedPlayedPerTeam);
            assertThat(row.wins() + row.draws() + row.losses()).isEqualTo(row.played());
            assertThat(row.points()).isEqualTo(row.wins() * 3 + row.draws());
            totalGoalsFor += row.goalsFor();
            totalGoalsAgainst += row.goalsAgainst();
            totalPlayed += row.played();
        }
        // Every goal is one team's for and another's against; every match counts twice across teams.
        assertThat(totalGoalsFor).isEqualTo(totalGoalsAgainst);
        int totalMatches = n * (n - 1) * seasons; // ordered pairs
        assertThat(totalPlayed).isEqualTo(totalMatches * 2);

        // Sorted by points descending.
        for (int i = 1; i < standings.size(); i++) {
            assertThat(standings.get(i - 1).points()).isGreaterThanOrEqualTo(standings.get(i).points());
        }
    }

    /** A real league (>= 3 distinct team ids) from the bootstrapped DB for the current season. */
    private List<Long> aLeague() {
        int season = gameState.currentSeason();
        for (long compId : gameState.getLeagueCompetitionIdsCached().stream().sorted().toList()) {
            TreeSet<Long> ids = new TreeSet<>();
            for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season))
                if (cti.getTeamId() > 0) ids.add(cti.getTeamId());
            if (ids.size() >= 3) return new ArrayList<>(ids);
        }
        throw new IllegalStateException("no league with >=3 teams in the bootstrap");
    }
}
