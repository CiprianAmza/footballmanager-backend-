package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin coordinator for match simulation. Round-level work lives in
 * {@link MatchRoundSimulator}; calendar-driven matchday dispatch + result
 * lookups + interactive-live-match commit live in {@link MatchdayCoordinator}.
 *
 * <p>This class keeps its original surface as delegate methods so existing
 * callers ({@link CompetitionController}, {@code GameAdvanceService},
 * {@code MatchController}, {@code LineupRatingService}) don't churn.
 */
@Service
public class MatchSimulationOrchestrator {

    @Autowired private MatchRoundSimulator matchRoundSimulator;
    @Autowired private MatchdayCoordinator matchdayCoordinator;

    // ===== Round-level =====

    public void simulateRound(String competitionId, String roundId) {
        matchRoundSimulator.simulateRound(competitionId, roundId);
    }

    public void processInjuriesForTeam(long teamId) {
        matchRoundSimulator.processInjuriesForTeam(teamId);
    }

    public List<Human> roundPlayers(long teamId) {
        return matchRoundSimulator.roundPlayers(teamId);
    }

    public Set<Long> roundInjuredIds(long teamId) {
        return matchRoundSimulator.roundInjuredIds(teamId);
    }

    public String roundTeamName(long teamId) {
        return matchRoundSimulator.roundTeamName(teamId);
    }

    public Team roundTeam(long teamId) {
        return matchRoundSimulator.roundTeam(teamId);
    }

    // ===== Matchday-level =====

    public void simulateMatchday(long competitionId, int matchday, int season) {
        matchdayCoordinator.simulateMatchday(competitionId, matchday, season);
    }

    public void appendKnockoutWinnerGoal(long competitionId, int season, int roundNumber,
                                         long teamId1, long teamId2,
                                         long winnerTeamId, long loserTeamId) {
        matchdayCoordinator.appendKnockoutWinnerGoal(competitionId, season, roundNumber,
                teamId1, teamId2, winnerTeamId, loserTeamId);
    }

    public List<Map<String, Object>> getAllMatchResults(long competitionId, int matchday, int season) {
        return matchdayCoordinator.getAllMatchResults(competitionId, matchday, season);
    }

    public Map<String, Object> getHumanMatchResult(long competitionId, int matchday, int season, long humanTeamId) {
        return matchdayCoordinator.getHumanMatchResult(competitionId, matchday, season, humanTeamId);
    }

    public Map<String, Object> finalizeInteractiveLiveMatch(String liveKey) {
        return matchdayCoordinator.finalizeInteractiveLiveMatch(liveKey);
    }
}
