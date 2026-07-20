package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.AwardWeightingConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AwardServiceTest {

    @Test
    void persistsWeightedGoldenBootAndDeterministicBallonDorVotingEvidence() {
        AwardService service = new AwardService();
        AwardRepository awardRepository = mock(AwardRepository.class);
        AwardOverrideRepository awardOverrideRepository = mock(AwardOverrideRepository.class);
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        ReflectionTestUtils.setField(service, "awardRepository", awardRepository);
        ReflectionTestUtils.setField(service, "awardOverrideRepository", awardOverrideRepository);
        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        configureAwardWeights(service, Map.of(1L, 4.0, 3L, 1.0), Map.of(101L, 4.0, 202L, 1.0));

        List<Scorer> appearances = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            appearances.add(scorer(11L, 101L, 1, index == 0 ? 8 : 0, index == 1 ? 4 : 0, 9.2));
            appearances.add(scorer(22L, 202L, 3, index == 0 ? 14 : 0, 0, 7.0));
        }
        when(scorerRepository.findAllBySeasonNumber(4)).thenReturn(appearances);
        when(awardRepository.existsBySeasonNumberAndAwardType(4, "GOLDEN_BOOT")).thenReturn(false);
        when(awardRepository.existsBySeasonNumberAndAwardType(4, "BALLON_DOR")).thenReturn(false);
        when(awardRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(humanRepository.findById(11L)).thenReturn(Optional.of(human(11L, 101L, "Complete Forward")));
        when(humanRepository.findById(22L)).thenReturn(Optional.of(human(22L, 202L, "Second Tier Scorer")));

        List<Award> awards = service.ensureMajorAwardsForSeason(4);

        assertEquals(2, awards.size());
        Award goldenBoot = awards.stream().filter(a -> "GOLDEN_BOOT".equals(a.getAwardType())).findFirst().orElseThrow();
        assertEquals(11L, goldenBoot.getWinnerId());
        assertEquals(96.0, goldenBoot.getVotingPoints());
        Award ballonDor = awards.stream().filter(a -> "BALLON_DOR".equals(a.getAwardType())).findFirst().orElseThrow();
        assertEquals(11L, ballonDor.getWinnerId());
        assertEquals(12, ballonDor.getAppearances());
        assertTrue(ballonDor.getVotingPoints() > 0);
        assertTrue(ballonDor.getFirstPlaceVotes() > 0);
    }

    @Test
    void adminBallonDorSelectionOverridesTheVoteButKeepsStatisticalEvidence() {
        AwardService service = new AwardService();
        AwardRepository awardRepository = mock(AwardRepository.class);
        AwardOverrideRepository awardOverrideRepository = mock(AwardOverrideRepository.class);
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        ReflectionTestUtils.setField(service, "awardRepository", awardRepository);
        ReflectionTestUtils.setField(service, "awardOverrideRepository", awardOverrideRepository);
        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        configureAwardWeights(service, Map.of(1L, 1.0), Map.of(101L, 1.0, 202L, 1.0));

        List<Scorer> appearances = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            appearances.add(scorer(11L, 101L, 1, index == 0 ? 12 : 0, 0, 9.4));
            appearances.add(scorer(22L, 202L, 1, index == 0 ? 2 : 0, index == 1 ? 2 : 0, 7.1));
        }
        AwardOverride override = new AwardOverride();
        override.setSeasonNumber(4);
        override.setCompetitionId(0L);
        override.setAwardType("BALLON_DOR");
        override.setWinnerId(22L);

        when(scorerRepository.findAllBySeasonNumber(4)).thenReturn(appearances);
        when(awardRepository.existsBySeasonNumberAndAwardType(4, "GOLDEN_BOOT")).thenReturn(true);
        when(awardRepository.existsBySeasonNumberAndAwardType(4, "BALLON_DOR")).thenReturn(false);
        when(awardOverrideRepository.findBySeasonNumberAndCompetitionIdAndAwardType(
                4, 0L, "BALLON_DOR")).thenReturn(Optional.of(override));
        when(awardRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(humanRepository.findById(11L)).thenReturn(Optional.of(human(11L, 101L, "Vote Leader")));
        when(humanRepository.findById(22L)).thenReturn(Optional.of(human(22L, 202L, "Admin Selection")));

        Award ballonDor = service.ensureMajorAwardsForSeason(4).get(0);

        assertEquals(22L, ballonDor.getWinnerId());
        assertTrue(ballonDor.isAdminSelected());
        assertTrue(ballonDor.getVotingPoints() > 0);
        assertTrue(ballonDor.getValue().contains("admin selection"));
    }

    @Test
    void createsCompleteGlobalAndCompetitionAwardSetsOnlyOnce() {
        AwardService service = new AwardService();
        AwardRepository awardRepository = mock(AwardRepository.class);
        ScorerRepository scorerRepository = mock(ScorerRepository.class);
        HumanRepository humanRepository = mock(HumanRepository.class);
        PlayerSeasonStatRepository playerStatsRepository = mock(PlayerSeasonStatRepository.class);
        MatchStatsRepository matchStatsRepository = mock(MatchStatsRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(service, "awardRepository", awardRepository);
        ReflectionTestUtils.setField(service, "scorerRepository", scorerRepository);
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(service, "playerSeasonStatRepository", playerStatsRepository);
        ReflectionTestUtils.setField(service, "matchStatsRepository", matchStatsRepository);
        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        configureAwardWeights(service, Map.of(1L, 4.0), Map.of(101L, 4.0));

        Competition competition = new Competition();
        competition.setId(1L);
        competition.setName("Test Premier League");
        competition.setTypeId(1L);
        when(competitionRepository.findAll()).thenReturn(List.of(competition));
        when(awardRepository.findAllBySeasonNumber(4)).thenReturn(List.of());

        String[] positions = {"GK", "DL", "DC", "DC", "DR", "MC", "MC", "AML", "AMC", "AMR", "ST"};
        List<Scorer> appearances = new ArrayList<>();
        List<PlayerSeasonStat> seasonStats = new ArrayList<>();
        for (int player = 0; player < positions.length; player++) {
            long playerId = player + 1L;
            for (int round = 1; round <= 6; round++) {
                Scorer row = scorer(playerId, 101L, 1,
                        player == 10 && round <= 4 ? 1 : 0,
                        player == 8 && round <= 5 ? 1 : 0,
                        7.0 + player * 0.08);
                row.setCompetitionId(1L);
                row.setRoundNumber(round);
                row.setPosition(positions[player]);
                appearances.add(row);
            }
            PlayerSeasonStat stat = new PlayerSeasonStat();
            stat.setPlayerId(playerId);
            stat.setTeamId(101L);
            stat.setCompetitionId(1L);
            stat.setSeasonNumber(4);
            stat.setAppearances(6);
            stat.setDribblesCompleted(player == 7 ? 42 : player + 2);
            stat.setChancesCreated(player == 7 ? 25 : player + 1);
            seasonStats.add(stat);
            when(humanRepository.findById(playerId))
                    .thenReturn(Optional.of(human(playerId, 101L, "Player " + playerId)));
        }
        when(scorerRepository.findAllBySeasonNumber(4)).thenReturn(appearances);
        when(playerStatsRepository.findAllBySeasonNumber(4)).thenReturn(seasonStats);

        MatchStats match = new MatchStats();
        match.setCompetitionId(1L);
        match.setSeasonNumber(4);
        match.setRoundNumber(1);
        match.setTeam1Id(101L);
        match.setTeam2Id(202L);
        match.setHomeGoals(2);
        match.setAwayGoals(0);
        match.setHomeSaves(4);
        match.setAwaySaves(1);
        when(matchStatsRepository.findAllBySeasonNumber(4)).thenReturn(List.of(match));

        Team alpha = new Team();
        alpha.setId(101L);
        alpha.setName("Alpha");
        alpha.setReputation(7000);
        Team beta = new Team();
        beta.setId(202L);
        beta.setName("Beta");
        beta.setReputation(8000);
        when(teamRepository.findAll()).thenReturn(List.of(alpha, beta));
        Human manager = human(900L, 101L, "Winning Manager");
        manager.setTypeId(2);
        when(humanRepository.findAllByTypeId(anyLong())).thenReturn(List.of(manager));
        when(userRepository.findAll()).thenReturn(List.of());
        when(awardRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<Award> created = service.ensureComprehensiveAwardsForSeason(4);

        assertEquals(22, created.stream().filter(a -> "TEAM_OF_YEAR".equals(a.getAwardType())).count());
        assertEquals(2, created.stream().filter(a -> "PLAYER_OF_YEAR".equals(a.getAwardType())).count());
        assertEquals(2, created.stream().filter(a -> "MOST_ENTERTAINING".equals(a.getAwardType())).count());
        assertEquals(2, created.stream().filter(a -> "BEST_GOALKEEPER".equals(a.getAwardType())).count());
        Award entertaining = created.stream()
                .filter(a -> "MOST_ENTERTAINING".equals(a.getAwardType()) && a.getCompetitionId() == 1L)
                .findFirst().orElseThrow();
        assertEquals(8L, entertaining.getWinnerId());
        assertEquals(42.0, entertaining.getDribblesCompleted());

        when(awardRepository.findAllBySeasonNumber(4)).thenReturn(created);
        assertTrue(service.ensureComprehensiveAwardsForSeason(4).isEmpty());
    }

    private Scorer scorer(long playerId, long teamId, int competitionType, int goals, int assists,
                          double rating) {
        Scorer scorer = new Scorer();
        scorer.setPlayerId(playerId);
        scorer.setTeamId(teamId);
        scorer.setTeamName(teamId == 101L ? "Alpha" : "Beta");
        scorer.setOpponentTeamId(999L);
        scorer.setCompetitionTypeId(competitionType);
        scorer.setCompetitionId(competitionType);
        scorer.setSeasonNumber(4);
        scorer.setRoundNumber(1);
        scorer.setTeamScore(Math.max(1, goals));
        scorer.setOpponentScore(0);
        scorer.setGoals(goals);
        scorer.setAssists(assists);
        scorer.setRating(rating);
        return scorer;
    }

    private void configureAwardWeights(AwardService service,
                                       Map<Long, Double> competitionMultipliers,
                                       Map<Long, Double> teamMultipliers) {
        AwardWeightingConfig config = new AwardWeightingConfig();
        LeagueStrengthService leagueStrengthService = mock(LeagueStrengthService.class);
        LeagueStrengthService.LeagueStrengthTable table = new LeagueStrengthService.LeagueStrengthTable(
                4, 11, 1.0, List.of(), List.of(), competitionMultipliers, teamMultipliers);
        when(leagueStrengthService.calculate(4)).thenReturn(table);
        ReflectionTestUtils.setField(service, "awardWeightingConfig", config);
        ReflectionTestUtils.setField(service, "leagueStrengthService", leagueStrengthService);
    }

    private Human human(long id, long teamId, String name) {
        Human human = new Human();
        human.setId(id);
        human.setTeamId(teamId);
        human.setName(name);
        return human;
    }
}
