package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.AwardWeightingConfig;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionStatLine;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

class StatsAggregationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void competitionRatingImpactUsesTeamMatchThresholdAndExcludesFiveOfTenAppearances() {
        StatsAggregationService service = new StatsAggregationService();
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        MatchStatsRepository matchStatsRepository = mock(MatchStatsRepository.class);
        CompetitionHistoryRepository historyRepository = mock(CompetitionHistoryRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "matchStatsRepository", matchStatsRepository);
        ReflectionTestUtils.setField(service, "competitionHistoryRepository", historyRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);

        long competitionId = 14L;
        int season = 7;
        long teamId = 86L;
        List<Scorer> performances = new java.util.ArrayList<>();
        addRatedAppearances(performances, 1L, teamId, competitionId, season, 6, 9.0);
        addRatedAppearances(performances, 2L, teamId, competitionId, season, 5, 9.8);
        addRatedAppearances(performances, 3L, teamId, competitionId, season, 10, 7.0);
        when(scorerRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season))
                .thenReturn(performances);

        List<MatchStats> matches = new java.util.ArrayList<>();
        for (int round = 1; round <= 10; round++) {
            MatchStats match = new MatchStats();
            match.setTeam1Id(teamId);
            match.setTeam2Id(100L + round);
            matches.add(match);
        }
        when(matchStatsRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season))
                .thenReturn(matches);
        when(historyRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season)).thenReturn(List.of());
        when(humanRepository.findAllById(any())).thenReturn(List.of(
                human(1L, teamId, "Eligible star"),
                human(2L, teamId, "Ineligible star"),
                human(3L, teamId, "Regular")));
        when(teamRepository.findAllById(any())).thenReturn(List.of(team(teamId, "Sherlock FC")));

        Map<String, Object> result = service.getCompetitionRatingImpact(competitionId, season);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");

        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).get("playerId"));
        assertEquals(6, rows.get(0).get("requiredAppearances"));
        assertEquals(6, rows.get(0).get("appearances"));
        assertEquals(10, rows.get(0).get("teamMatches"));
        assertEquals(9.0, rows.get(0).get("playerRating"));
        assertEquals(8.24, rows.get(0).get("teamRating"));
        assertEquals(0.76, rows.get(0).get("difference"));
        assertEquals(55, result.get("minimumAppearancePercentage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ratingHistoryRequiresBothTeamAndCompetitionAppearanceThresholds() {
        StatsAggregationService service = new StatsAggregationService();
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        MatchStatsRepository matchStatsRepository = mock(MatchStatsRepository.class);
        CompetitionHistoryRepository historyRepository = mock(CompetitionHistoryRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "matchStatsRepository", matchStatsRepository);
        ReflectionTestUtils.setField(service, "competitionHistoryRepository", historyRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);

        long competitionId = 14L;
        ScorerRepository.RatingImpactHistoryAggregate oneMatchWonder =
                ratingAggregate(competitionId, 7, 86L, 1L, 1, 9.8);
        ScorerRepository.RatingImpactHistoryAggregate eligibleLeader =
                ratingAggregate(competitionId, 7, 87L, 2L, 6, 45.0);
        ScorerRepository.RatingImpactHistoryAggregate regular =
                ratingAggregate(competitionId, 7, 87L, 3L, 10, 60.0);
        when(scorerRepository.aggregateRatingImpactHistory()).thenReturn(List.of(
                oneMatchWonder, eligibleLeader, regular));
        when(matchStatsRepository.findAllByCompetitionIdIn(any())).thenReturn(List.of());

        CompetitionHistory preliminaryExit = competitionHistory(competitionId, 7, 86L, 1);
        CompetitionHistory finalist = competitionHistory(competitionId, 7, 87L, 10);
        when(historyRepository.findAllByCompetitionIdIn(any()))
                .thenReturn(List.of(preliminaryExit, finalist));
        when(humanRepository.findAllById(any())).thenReturn(List.of(
                human(1L, 86L, "One-match wonder"),
                human(2L, 87L, "Eligible leader"),
                human(3L, 87L, "Regular")));
        when(teamRepository.findAllById(any())).thenReturn(List.of(
                team(86L, "Qualifier"), team(87L, "Finalist")));
        Competition competition = competition(competitionId, 4);
        competition.setName("League of Champions");
        when(competitionRepository.findAllById(any())).thenReturn(List.of(competition));

        Map<String, Object> result = service.getRatingImpactHistory(55, 60);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");

        assertEquals(1, rows.size());
        assertEquals(2L, rows.get(0).get("playerId"));
        assertEquals(10, rows.get(0).get("competitionMatches"));
        assertEquals(6, rows.get(0).get("requiredTeamAppearances"));
        assertEquals(6, rows.get(0).get("requiredCompetitionAppearances"));
        assertEquals(6, rows.get(0).get("requiredAppearances"));
        assertEquals(55, result.get("teamAppearancePercentage"));
        assertEquals(60, result.get("competitionAppearancePercentage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allTimeChampionsExposeCompetitionIdsCountSuperCupsAndIgnoreDuplicateSnapshots() {
        StatsAggregationService service = new StatsAggregationService();
        CompetitionHistoryRepository historyRepository = mock(CompetitionHistoryRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        ReflectionTestUtils.setField(service, "competitionHistoryRepository", historyRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);

        CompetitionHistory league = titleHistory(1L, 1, 10L, 1, "Test League", 1L);
        CompetitionHistory duplicatedLeague = titleHistory(1L, 1, 10L, 1, "Test League", 2L);
        CompetitionHistory superCup = titleHistory(6L, 2, 10L, 6, "Test Super Cup", 3L);
        CompetitionHistory runnerUp = titleHistory(6L, 2, 20L, 6, "Test Super Cup", 4L);
        runnerUp.setLastPosition(2);
        when(historyRepository.findAll()).thenReturn(List.of(
                league, duplicatedLeague, superCup, runnerUp));
        when(teamRepository.findAllById(any())).thenReturn(List.of(team(10L, "Alpha")));

        List<Map<String, Object>> result = service.getAllTimeChampions();

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).get("totalTitles"));
        assertEquals(1, result.get(0).get("leagueTitles"));
        assertEquals(1, result.get(0).get("superCupTitles"));
        List<Map<String, Object>> titles = (List<Map<String, Object>>) result.get(0).get("titles");
        assertEquals(6L, titles.get(0).get("competitionId"));
        assertEquals(2L, titles.get(0).get("season"));
        assertEquals(1L, titles.get(1).get("competitionId"));
    }

    @Test
    void teamSeasonStatsUsePlayedMatchesAndRankByGoalContributions() {
        StatsAggregationService service = new StatsAggregationService();
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);

        Scorer creator = seasonScorer(11L, 86L, 1, 2, 5);
        creator.setRating(7.2);
        Scorer scorer = seasonScorer(22L, 86L, 1, 4, 1);
        scorer.setRating(8.0);
        Scorer placeholder = seasonScorer(33L, 86L, 1, 20, 20);
        placeholder.setTeamScore(-1);
        placeholder.setOpponentTeamId(-1);
        when(scorerRepository.findAllByTeamIdAndSeasonNumber(86L, 3))
                .thenReturn(List.of(scorer, placeholder, creator));
        Human creatorPlayer = human(11L, 86L, "Creator");
        creatorPlayer.setPosition("AMC");
        Human scorerPlayer = human(22L, 86L, "Scorer");
        scorerPlayer.setPosition("ST");
        when(humanRepository.findAllById(any())).thenReturn(List.of(creatorPlayer, scorerPlayer));

        List<Map<String, Object>> result = service.getTeamSeasonPlayerStats(86L, 3, 3);

        assertEquals(2, result.size());
        assertEquals(11L, result.get(0).get("playerId"));
        assertEquals(7, result.get(0).get("goalContributions"));
        assertEquals(5, result.get(0).get("assists"));
        assertEquals(7.2, result.get(0).get("averageRating"));
        assertEquals(1, result.get(0).get("rank"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewGoldenBootAppliesLeagueWeightsAndBuildsAnalyticsLeaders() {
        StatsAggregationService service = new StatsAggregationService();
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        PlayerSeasonStatRepository seasonStatRepository = mock(PlayerSeasonStatRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        LeagueStrengthService leagueStrengthService = mock(LeagueStrengthService.class);
        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "playerSeasonStatRepository", seasonStatRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "leagueStrengthService", leagueStrengthService);
        ReflectionTestUtils.setField(service, "awardWeightingConfig", new AwardWeightingConfig());
        when(leagueStrengthService.calculate(3)).thenReturn(strengthTable(
                Map.of(1L, 4.0, 3L, 1.0), Map.of(101L, 4.0, 202L, 1.0)));

        long firstLeaguePlayer = 11L;
        long secondLeaguePlayer = 22L;
        when(scorerRepository.findAllBySeasonNumber(3)).thenReturn(List.of(
                seasonScorer(firstLeaguePlayer, 101L, 1, 8, 1),
                seasonScorer(secondLeaguePlayer, 202L, 3, 14, 2)));

        PlayerSeasonStat creator = new PlayerSeasonStat();
        creator.setPlayerId(secondLeaguePlayer);
        creator.setTeamId(202L);
        creator.setSeasonNumber(3);
        creator.setCompetitionId(303L);
        creator.setAppearances(4);
        creator.setMinutes(360);
        creator.setPassesCompleted(210);
        creator.setChancesCreated(12.4);
        when(seasonStatRepository.findAllBySeasonNumber(3)).thenReturn(List.of(creator));

        Human first = human(firstLeaguePlayer, 101L, "First scorer");
        Human second = human(secondLeaguePlayer, 202L, "Second scorer");
        when(humanRepository.findAllById(any())).thenReturn(List.of(first, second));
        when(teamRepository.findAllById(any())).thenReturn(List.of(
                team(101L, "Alpha"), team(202L, "Beta")));
        Competition secondLeague = new Competition();
        secondLeague.setId(303L);
        secondLeague.setTypeId(3);
        when(competitionRepository.findAll()).thenReturn(List.of(secondLeague));

        Map<String, Object> result = service.getSeasonOverviewStats(3, 10);
        List<Map<String, Object>> goldenBoot = (List<Map<String, Object>>) result.get("goldenBoot");

        assertEquals(firstLeaguePlayer, goldenBoot.get(0).get("playerId"));
        assertEquals(84.0, goldenBoot.get(0).get("awardPoints"));
        assertEquals(37.0, goldenBoot.get(1).get("awardPoints"));

        List<Map<String, Object>> categories = (List<Map<String, Object>>) result.get("categories");
        Map<String, Object> chances = categories.stream()
                .filter(category -> "chancesCreated".equals(category.get("key")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> leaders = (List<Map<String, Object>>) chances.get("leaders");
        assertEquals(secondLeaguePlayer, leaders.get(0).get("playerId"));
        assertEquals(12.4, leaders.get(0).get("value"));
        assertEquals("LEAGUE", result.get("scope"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewAppliesTheSelectedScopeToGoalsAssistsAndAnalyticsTogether() {
        StatsAggregationService service = new StatsAggregationService();
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        PlayerSeasonStatRepository seasonStatRepository = mock(PlayerSeasonStatRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        LeagueStrengthService leagueStrengthService = mock(LeagueStrengthService.class);
        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "playerSeasonStatRepository", seasonStatRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "leagueStrengthService", leagueStrengthService);
        ReflectionTestUtils.setField(service, "awardWeightingConfig", new AwardWeightingConfig());
        when(leagueStrengthService.calculate(3)).thenReturn(strengthTable(Map.of(), Map.of()));

        Scorer league = seasonScorer(11L, 101L, 1, 3, 0);
        Scorer cup = seasonScorer(22L, 202L, 2, 1, 4);
        when(scorerRepository.findAllBySeasonNumber(3)).thenReturn(List.of(league, cup));

        PlayerSeasonStat leaguePasses = seasonStat(11L, 101L, 11L, 150);
        PlayerSeasonStat cupPasses = seasonStat(22L, 202L, 22L, 75);
        when(seasonStatRepository.findAllBySeasonNumber(3)).thenReturn(List.of(leaguePasses, cupPasses));
        when(humanRepository.findAllById(any())).thenReturn(List.of(
                human(11L, 101L, "League player"), human(22L, 202L, "Cup player")));
        when(teamRepository.findAllById(any())).thenReturn(List.of(
                team(101L, "Alpha"), team(202L, "Beta")));
        Competition leagueCompetition = competition(11L, 1);
        Competition cupCompetition = competition(22L, 2);
        when(competitionRepository.findAll()).thenReturn(List.of(leagueCompetition, cupCompetition));

        Map<String, Object> result = service.getSeasonOverviewStats(3, 10, "CUP");
        List<Map<String, Object>> scorers = (List<Map<String, Object>>) result.get("goldenBoot");
        assertEquals(1, scorers.size());
        assertEquals(22L, scorers.get(0).get("playerId"));

        List<Map<String, Object>> categories = (List<Map<String, Object>>) result.get("categories");
        List<Map<String, Object>> assists = (List<Map<String, Object>>) categories.stream()
                .filter(category -> "assists".equals(category.get("key"))).findFirst().orElseThrow().get("leaders");
        assertEquals(22L, assists.get(0).get("playerId"));
        List<Map<String, Object>> passes = (List<Map<String, Object>>) categories.stream()
                .filter(category -> "passesCompleted".equals(category.get("key"))).findFirst().orElseThrow().get("leaders");
        assertEquals(22L, passes.get(0).get("playerId"));
        assertEquals("CUP", result.get("scope"));
    }

    @Test
    void historicalSeasonUsesArchivedPositionWhileCurrentSeasonUsesLiveStandings() {
        StatsAggregationService service = new StatsAggregationService();
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        CompetitionHistoryRepository historyRepository = mock(CompetitionHistoryRepository.class);
        TeamCompetitionDetailRepository standingsRepository = mock(TeamCompetitionDetailRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionTeamInfoRepository entryRepository = mock(CompetitionTeamInfoRepository.class);
        CompetitionTeamInfoDetailRepository resultRepository = mock(CompetitionTeamInfoDetailRepository.class);
        RoundRepository roundRepository = mock(RoundRepository.class);
        CompetitionProgressService progressService = mock(CompetitionProgressService.class);

        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "competitionHistoryRepository", historyRepository);
        ReflectionTestUtils.setField(service, "teamCompetitionDetailRepository", standingsRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoRepository", entryRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoDetailRepository", resultRepository);
        ReflectionTestUtils.setField(service, "roundRepository", roundRepository);
        ReflectionTestUtils.setField(service, "competitionProgressService", progressService);

        long teamId = 10L;
        long competitionId = 3L;
        Team selectedTeam = team(teamId, "Selected");
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(selectedTeam));
        Round activeRound = new Round();
        activeRound.setSeason(2);
        when(roundRepository.findById(1L)).thenReturn(Optional.of(activeRound));
        when(entryRepository.findAllByTeamIdAndSeasonNumber(teamId, 2)).thenReturn(List.of());
        when(resultRepository.findAllByTeamId(teamId)).thenReturn(List.of());
        when(progressService.teamProgress(anyLong(), anyLong(), anyLong())).thenReturn(Map.of());
        when(scorerRepository.findAllByTeamId(teamId)).thenReturn(List.of(
                scorer(teamId, competitionId, 1, 2, 1),
                scorer(teamId, competitionId, 2, 0, 1)));

        CompetitionHistory seasonOne = new CompetitionHistory();
        seasonOne.setTeamId(teamId);
        seasonOne.setCompetitionId(competitionId);
        seasonOne.setCompetitionTypeId(1);
        seasonOne.setSeasonNumber(1);
        seasonOne.setLastPosition(3);
        when(historyRepository.findByTeamId(teamId)).thenReturn(List.of(seasonOne));

        TeamCompetitionDetail currentLeader = standing(20L, competitionId, 30);
        TeamCompetitionDetail currentTeam = standing(teamId, competitionId, 20);
        when(standingsRepository.findAllByCompetitionId(competitionId))
                .thenReturn(List.of(currentLeader, currentTeam));

        List<CompetitionStatLine> result = service.getTeamCompetitionBreakdown(teamId);

        CompetitionStatLine current = result.stream()
                .filter(line -> line.getSeasonNumber() == 2).findFirst().orElseThrow();
        CompetitionStatLine historical = result.stream()
                .filter(line -> line.getSeasonNumber() == 1).findFirst().orElseThrow();
        assertEquals(2, current.getLeaguePosition());
        assertEquals(3, historical.getLeaguePosition());
    }

    @Test
    void europeanQualifierUsesOfficialResultWhenNoPlayerScorerRowsExist() {
        StatsAggregationService service = new StatsAggregationService();
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        CompetitionHistoryRepository historyRepository = mock(CompetitionHistoryRepository.class);
        TeamCompetitionDetailRepository standingsRepository = mock(TeamCompetitionDetailRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        CompetitionTeamInfoRepository entryRepository = mock(CompetitionTeamInfoRepository.class);
        CompetitionTeamInfoDetailRepository resultRepository = mock(CompetitionTeamInfoDetailRepository.class);
        RoundRepository roundRepository = mock(RoundRepository.class);
        CompetitionProgressService progressService = mock(CompetitionProgressService.class);

        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "competitionHistoryRepository", historyRepository);
        ReflectionTestUtils.setField(service, "teamCompetitionDetailRepository", standingsRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoRepository", entryRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoDetailRepository", resultRepository);
        ReflectionTestUtils.setField(service, "roundRepository", roundRepository);
        ReflectionTestUtils.setField(service, "competitionProgressService", progressService);

        long teamId = 86L;
        long competitionId = 14L;
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team(teamId, "Sherlock FC")));
        when(scorerRepository.findAllByTeamId(teamId)).thenReturn(List.of());

        CompetitionHistory archived = new CompetitionHistory();
        archived.setTeamId(teamId);
        archived.setCompetitionId(competitionId);
        archived.setCompetitionName("League of Champions");
        archived.setCompetitionTypeId(4);
        archived.setSeasonNumber(7);
        when(historyRepository.findByTeamId(teamId)).thenReturn(List.of(archived));

        Round activeRound = new Round();
        activeRound.setSeason(11);
        when(roundRepository.findById(1L)).thenReturn(Optional.of(activeRound));
        when(entryRepository.findAllByTeamIdAndSeasonNumber(teamId, 11)).thenReturn(List.of());

        Competition competition = competition(competitionId, 4);
        competition.setName("League of Champions");
        when(competitionRepository.findById(competitionId)).thenReturn(Optional.of(competition));

        CompetitionTeamInfoDetail result = new CompetitionTeamInfoDetail();
        result.setCompetitionId(competitionId);
        result.setSeasonNumber(7);
        result.setTeam1Id(teamId);
        result.setTeam2Id(44L);
        result.setScore("0 - 1");
        when(resultRepository.findAllByTeamId(teamId)).thenReturn(List.of(result));
        when(progressService.teamProgress(teamId, competitionId, 7)).thenReturn(Map.of(
                "status", "ELIMINATED",
                "statusLabel", "Eliminated in Qualifying Round 1 by Ion Tara FC"));

        CompetitionStatLine line = service.getTeamCompetitionBreakdown(teamId).stream()
                .filter(candidate -> candidate.getCompetitionId() == competitionId
                        && candidate.getSeasonNumber() == 7)
                .findFirst().orElseThrow();

        assertEquals(1, line.getMatches());
        assertEquals(0, line.getWins());
        assertEquals(0, line.getDraws());
        assertEquals(1, line.getLosses());
        assertEquals(0, line.getGoalsFor());
        assertEquals(1, line.getGoalsAgainst());
        assertEquals("Eliminated in Qualifying Round 1 by Ion Tara FC", line.getStatusLabel());
    }

    private Scorer scorer(long teamId, long competitionId, int season,
                          int teamScore, int opponentScore) {
        Scorer scorer = new Scorer();
        scorer.setTeamId(teamId);
        scorer.setOpponentTeamId(99L);
        scorer.setCompetitionId(competitionId);
        scorer.setCompetitionName("Test League");
        scorer.setCompetitionTypeId(1);
        scorer.setSeasonNumber(season);
        scorer.setTeamScore(teamScore);
        scorer.setOpponentScore(opponentScore);
        return scorer;
    }

    private ScorerRepository.RatingImpactHistoryAggregate ratingAggregate(
            long competitionId, int season, long teamId, long playerId,
            int appearances, double ratingTotal) {
        ScorerRepository.RatingImpactHistoryAggregate aggregate =
                mock(ScorerRepository.RatingImpactHistoryAggregate.class);
        when(aggregate.getCompetitionId()).thenReturn(competitionId);
        when(aggregate.getCompetitionName()).thenReturn("League of Champions");
        when(aggregate.getCompetitionTypeId()).thenReturn(4);
        when(aggregate.getSeasonNumber()).thenReturn(season);
        when(aggregate.getTeamId()).thenReturn(teamId);
        when(aggregate.getTeamName()).thenReturn(teamId == 86L ? "Qualifier" : "Finalist");
        when(aggregate.getPlayerId()).thenReturn(playerId);
        when(aggregate.getAppearances()).thenReturn((long) appearances);
        when(aggregate.getRatingCount()).thenReturn((long) appearances);
        when(aggregate.getRatingTotal()).thenReturn(ratingTotal);
        return aggregate;
    }

    private CompetitionHistory competitionHistory(long competitionId, int season,
                                                    long teamId, int games) {
        CompetitionHistory history = new CompetitionHistory();
        history.setCompetitionId(competitionId);
        history.setCompetitionTypeId(4);
        history.setCompetitionName("League of Champions");
        history.setSeasonNumber(season);
        history.setTeamId(teamId);
        history.setGames(games);
        return history;
    }

    private CompetitionHistory titleHistory(long competitionId, long season, long teamId,
                                              long typeId, String name, long historyId) {
        CompetitionHistory history = new CompetitionHistory();
        history.setId(historyId);
        history.setCompetitionId(competitionId);
        history.setSeasonNumber(season);
        history.setTeamId(teamId);
        history.setCompetitionTypeId(typeId);
        history.setCompetitionName(name);
        history.setLastPosition(1);
        return history;
    }

    private Scorer seasonScorer(long playerId, long teamId, int competitionTypeId, int goals, int assists) {
        Scorer scorer = new Scorer();
        scorer.setPlayerId(playerId);
        scorer.setTeamId(teamId);
        scorer.setTeamName(teamId == 101L ? "Alpha" : "Beta");
        scorer.setOpponentTeamId(999L);
        scorer.setCompetitionId(competitionTypeId);
        scorer.setCompetitionTypeId(competitionTypeId);
        scorer.setSeasonNumber(3);
        scorer.setTeamScore(Math.max(1, goals));
        scorer.setOpponentScore(0);
        scorer.setGoals(goals);
        scorer.setAssists(assists);
        scorer.setRating(7.0);
        return scorer;
    }

    private void addRatedAppearances(List<Scorer> target, long playerId, long teamId,
                                     long competitionId, int season, int appearances, double rating) {
        for (int round = 1; round <= appearances; round++) {
            Scorer scorer = new Scorer();
            scorer.setPlayerId(playerId);
            scorer.setTeamId(teamId);
            scorer.setTeamName("Sherlock FC");
            scorer.setOpponentTeamId(100L + round);
            scorer.setCompetitionId(competitionId);
            scorer.setSeasonNumber(season);
            scorer.setRoundNumber(round);
            scorer.setTeamScore(1);
            scorer.setOpponentScore(0);
            scorer.setRating(rating);
            target.add(scorer);
        }
    }

    private Human human(long id, long teamId, String name) {
        Human human = new Human();
        human.setId(id);
        human.setTeamId(teamId);
        human.setName(name);
        return human;
    }

    private Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }

    private Competition competition(long id, long typeId) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setTypeId(typeId);
        return competition;
    }

    private PlayerSeasonStat seasonStat(long playerId, long teamId, long competitionId, double passes) {
        PlayerSeasonStat stat = new PlayerSeasonStat();
        stat.setPlayerId(playerId);
        stat.setTeamId(teamId);
        stat.setCompetitionId(competitionId);
        stat.setSeasonNumber(3);
        stat.setAppearances(1);
        stat.setMinutes(90);
        stat.setPassesCompleted(passes);
        return stat;
    }

    private LeagueStrengthService.LeagueStrengthTable strengthTable(
            Map<Long, Double> competitionMultipliers, Map<Long, Double> teamMultipliers) {
        return new LeagueStrengthService.LeagueStrengthTable(
                3, 11, 1.0,
                List.of(new LeagueStrengthService.RankMultiplierTier(3, 4.0),
                        new LeagueStrengthService.RankMultiplierTier(5, 3.0),
                        new LeagueStrengthService.RankMultiplierTier(7, 2.0)),
                List.of(), competitionMultipliers, teamMultipliers);
    }

    private TeamCompetitionDetail standing(long teamId, long competitionId, int points) {
        TeamCompetitionDetail detail = new TeamCompetitionDetail();
        detail.setTeamId(teamId);
        detail.setCompetitionId(competitionId);
        detail.setPoints(points);
        return detail;
    }
}
