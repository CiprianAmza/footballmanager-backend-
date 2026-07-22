package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.InstantMatchExecutor;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.LineupAdapter;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.matchplan.MatchPlanningService;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.service.knockout.KnockoutTieResolver;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two real Spring transactions race through the canonical /commit coordinator. The fixture
 * pessimistic lock must serialize them, and the second transaction must observe COMMITTED and
 * return without repeating any match side effect.
 */
@DataJpaTest
@Import({MatchdayCoordinator.class, MatchPlanService.class, MatchPlanningService.class,
        InstantMatchExecutor.class, com.footballmanagergamesimulator.matchplan.ContributionResolver.class,
        MatchEngineConfig.class})
class MatchdayCoordinatorCanonicalCommitConcurrencyTest {

    private static final long HOME = 10L;
    private static final long AWAY = 20L;
    private static final long COMPETITION = 100L;
    private static final int SEASON = 4;
    private static final int ROUND = 8;
    private static final String LIVE_KEY = "commit-race";

    @Autowired private MatchdayCoordinator coordinator;
    @Autowired private MatchPlanService matchPlanService;
    @Autowired private MatchPlanRepository planRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private CompetitionTeamInfoDetailRepository detailRepository;

    @MockBean private LineupAdapter lineupAdapter;
    @MockBean private UserContext userContext;
    @MockBean private HumanRepository humanRepository;
    @MockBean private CompetitionRepository competitionRepository;
    @MockBean private RoundRepository roundRepository;
    @MockBean private LiveMatchSimulationService liveMatchSimulationService;
    @MockBean private MatchSimulationService matchSimulationService;
    @MockBean private MatchStatsService matchStatsService;
    @MockBean private TeamPostMatchService teamPostMatchService;
    @MockBean private LineupRatingService lineupRatingService;
    @MockBean private EuropeanCompetitionService europeanCompetitionService;
    @MockBean private EuropeanCoefficientService europeanCoefficientService;
    @MockBean private CompetitionDisplayService competitionDisplayService;
    @MockBean private FixtureSchedulingService fixtureSchedulingService;
    @MockBean private MatchRoundSimulator matchRoundSimulator;
    @MockBean private CompetitionFormatConfig competitionFormat;
    @MockBean private KnockoutTieResolver tieResolver;
    @MockBean private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @MockBean private CupBracketService cupBracketService;
    @MockBean private GameStateService gameStateService;
    @MockBean private EuropeanFixturePreparationService europeanFixturePreparationService;

    @Test
    void twoConcurrentCommitsPersistEverySideEffectExactlyOnce() throws Exception {
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(COMPETITION);
        fixture.setSeasonNumber(String.valueOf(SEASON));
        fixture.setRound(ROUND);
        fixture.setTeam1Id(HOME);
        fixture.setTeam2Id(AWAY);
        fixture = fixtureRepository.saveAndFlush(fixture);
        String fixtureKey = MatchPlanService.competitionFixtureKey(fixture.getId());
        matchPlanService.prepareLivePlan(fixtureKey, COMPETITION, SEASON, ROUND, HOME, AWAY,
                lineup(100), lineup(200), 0, 0);

        LiveMatchSession session = mock(LiveMatchSession.class);
        when(session.isFinished()).thenReturn(true);
        when(session.isCommitted()).thenReturn(false); // force durable plan idempotency to decide
        when(session.isCanonicalPlanBound()).thenReturn(true);
        when(session.getCanonicalFixtureKey()).thenReturn(fixtureKey);
        when(session.getTeamId1()).thenReturn(HOME);
        when(session.getTeamId2()).thenReturn(AWAY);
        when(session.getCompetitionId()).thenReturn(COMPETITION);
        when(session.getSeason()).thenReturn(SEASON);
        when(session.getRound()).thenReturn(ROUND);
        when(session.getHomeScore()).thenReturn(0);
        when(session.getAwayScore()).thenReturn(0);
        when(session.getDeferredTactic1()).thenReturn("442");
        when(session.getDeferredTactic2()).thenReturn("442");
        when(session.isDeferredKnockout()).thenReturn(false);
        when(session.nonGoalDbEvents()).thenReturn(List.of());
        when(session.asLiveMatchData()).thenReturn(new LiveMatchData());
        when(liveMatchSimulationService.getSession(LIVE_KEY)).thenReturn(session);
        when(liveMatchSimulationService.resolveCommitOutcome(session)).thenReturn(
                new LiveMatchSimulationService.CommitOutcome(0, 0, 100, 100, null, null, false));
        when(matchRoundSimulator.roundTeamName(HOME)).thenReturn("Home FC");
        when(matchRoundSimulator.roundTeamName(AWAY)).thenReturn("Away FC");

        // Worker transactions must see the fixture + prepared plan.
        TestTransaction.flagForCommit();
        TestTransaction.end();

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                start.await();
                return coordinator.finalizeInteractiveLiveMatch(LIVE_KEY);
            });
            var two = executor.submit(() -> {
                start.await();
                return coordinator.finalizeInteractiveLiveMatch(LIVE_KEY);
            });
            start.countDown();

            Map<String, Object> first = one.get(15, TimeUnit.SECONDS);
            Map<String, Object> second = two.get(15, TimeUnit.SECONDS);
            long already = List.of(first, second).stream()
                    .filter(result -> Boolean.TRUE.equals(result.get("alreadyCommitted"))).count();
            assertEquals(1, already, "exactly one transaction loses the race after durable commit");
        }

        assertEquals(MatchPlan.Status.COMMITTED,
                planRepository.findByFixtureKey(fixtureKey).orElseThrow().getStatus());
        assertEquals(1, detailRepository.count(), "one result detail row");
        CompetitionTeamInfoMatch stored = fixtureRepository.findById(fixture.getId()).orElseThrow();
        assertEquals(0, stored.getTeam1Score());
        assertEquals(0, stored.getTeam2Score());

        verify(teamPostMatchService, times(2)).updateTeam(anyLong(), anyLong(), anyInt(), anyInt(),
                anyDouble(), anyLong()); // one call per team, from one commit only
        verify(europeanCoefficientService, times(1)).awardCoefficientPoints(
                COMPETITION, ROUND, HOME, AWAY, 0, 0);
        verify(teamPostMatchService, times(1)).generateMatchReport(COMPETITION, ROUND, HOME, AWAY, 0, 0);
        verify(lineupRatingService, times(2)).persistPlayerRatings(anyLong(), anyInt(), anyInt(),
                anyLong(), any());
        assertTrue(detailRepository.findAll().get(0).getScore().startsWith("0 - 0"));
    }

    private Lineup lineup(long base) {
        String[] positions = {"GK", "DC", "DC", "DL", "DR", "MC", "MC", "AML", "AMR", "ST", "ST"};
        java.util.ArrayList<Contributor> xi = new java.util.ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            xi.add(new Contributor(base + i, "P" + (base + i), positions[i],
                    15.0, 14, 14, 14, 100.0, false, false));
        }
        return new Lineup(xi, List.of(), List.of());
    }
}
