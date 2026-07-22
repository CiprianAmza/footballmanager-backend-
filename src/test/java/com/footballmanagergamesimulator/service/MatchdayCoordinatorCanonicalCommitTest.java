package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.GoalPhase;
import com.footballmanagergamesimulator.matchplan.LivePlanSnapshot;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.TacticalScoreService.Matchup;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Coordinator-level proof of the canonical LIVE commit (feature flag ON):
 * {@link MatchdayCoordinator#finalizeInteractiveLiveMatch} must finalize the
 * canonical plan ({@code finishLivePlan} → {@code markCommitted}) and project the
 * Scorer leaderboard from the persisted canonical events (the 8-arg
 * {@code getScorersForTeam} tally overload), NOT the legacy RNG 7-arg overload.
 */
class MatchdayCoordinatorCanonicalCommitTest {

    private static final long HOME_TEAM = 1L;
    private static final long AWAY_TEAM = 2L;
    private static final long COMP = 10L;
    private static final long MATCH_ROW = 999L;
    private static final String FIXTURE_KEY = "CTIM:" + MATCH_ROW;
    private static final String LIVE_KEY = "k";

    private LiveMatchSimulationService realLiveService;   // builds the real bound session
    private MatchPlanService matchPlanService;
    private MatchEngineConfig engineConfig;
    private TacticalScoreService tacticalScoreService;

    @BeforeEach
    void setUp() throws Exception {
        engineConfig = new MatchEngineConfig();
        engineConfig.getTacticalModel().setEnabled(true);
        engineConfig.getMatchPlan().setEnabled(true);

        realLiveService = new LiveMatchSimulationService();
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
        GoalAnimationService goalAnimationService = mock(GoalAnimationService.class);
        matchPlanService = mock(MatchPlanService.class);

        inject(LiveMatchSimulationService.class, realLiveService, "humanRepository", humanRepository);
        inject(LiveMatchSimulationService.class, realLiveService, "teamRepository", teamRepository);
        inject(LiveMatchSimulationService.class, realLiveService, "competitionRepository", competitionRepository);
        inject(LiveMatchSimulationService.class, realLiveService, "playerSkillsRepository", playerSkillsRepository);
        inject(LiveMatchSimulationService.class, realLiveService, "matchEventRepository", mock(MatchEventRepository.class));
        inject(LiveMatchSimulationService.class, realLiveService, "goalAnimationService", goalAnimationService);
        inject(LiveMatchSimulationService.class, realLiveService, "engineConfig", engineConfig);
        inject(LiveMatchSimulationService.class, realLiveService, "matchPlanService", matchPlanService);

        tacticalScoreService = new TacticalScoreService();
        inject(TacticalScoreService.class, tacticalScoreService, "engineConfig", engineConfig);

        when(humanRepository.findAllByTeamIdAndTypeId(HOME_TEAM, 1L)).thenReturn(buildSquad(100L));
        when(humanRepository.findAllByTeamIdAndTypeId(AWAY_TEAM, 1L)).thenReturn(buildSquad(200L));
        when(teamRepository.findNameById(anyLong())).thenReturn("Team");
        when(competitionRepository.findNameById(COMP)).thenReturn("League");
        when(playerSkillsRepository.findAllByPlayerIdIn(any())).thenReturn(Collections.emptyList());
        when(matchPlanService.isEnabled()).thenReturn(true);
        when(matchPlanService.buildKickoffLineups(anyString(), anyLong(), anyInt(), anyInt(),
                anyLong(), anyLong(), any(), any()))
                .thenReturn(new MatchPlanService.KickoffLineups(kickoffXi(100), kickoffXi(200)));

        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        LivePlanSnapshot snap = new LivePlanSnapshot(FIXTURE_KEY, 12345L, HOME_TEAM, AWAY_TEAM,
                com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(slot(0, HOME_TEAM, 20), slot(1, HOME_TEAM, 60), slot(2, AWAY_TEAM, 40)),
                participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot(FIXTURE_KEY)).thenReturn(java.util.Optional.of(snap));
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> {
                    List<Contributor> onPitch = inv.getArgument(5);
                    return List.of(goalEvent(onPitch.isEmpty() ? 0L : onPitch.get(0).playerId(),
                            HOME_TEAM)); // team not used by the session for display
                });
    }

    @Test
    void canonicalCommit_finalizesPlanAndProjectsScorersFromCanonicalEvents() throws Exception {
        // 1) Build a real canonical-bound session and play it to full time.
        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = realLiveService.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 1,
                false, matchup, 2, 1, MATCH_ROW, "442", "442");
        session.setDeferredContext(100.0, 80.0, "442", "442", null, null, false, 0, 0L, 0);
        session.advanceUntilAndSnapshot(session.getTotalMinutes());

        // 2) Coordinator with mocked collaborators; the canonical branch must fire.
        MatchdayCoordinator coordinator = new MatchdayCoordinator();
        LiveMatchSimulationService liveMock = mock(LiveMatchSimulationService.class);
        when(liveMock.getSession(LIVE_KEY)).thenReturn(session);
        // Finding 1: even if resolveCommitOutcome RESCORES (recalculated=true with a wholly
        // different score), canonical commit must ignore it and use the session's fixed
        // canonical score.
        int canonicalHome = session.getHomeScore();
        int canonicalAway = session.getAwayScore();
        when(liveMock.resolveCommitOutcome(session)).thenReturn(
                new LiveMatchSimulationService.CommitOutcome(99, 98, 100.0, 80.0, null, null, true));

        MatchEventRepository matchEventRepository = mock(MatchEventRepository.class);
        when(matchEventRepository.findByFixtureKey(FIXTURE_KEY)).thenReturn(List.of(
                goalEvent(105L, HOME_TEAM), goalEvent(106L, HOME_TEAM), goalEvent(205L, AWAY_TEAM)));
        MatchRoundSimulator matchRoundSimulator = mock(MatchRoundSimulator.class);
        when(matchRoundSimulator.roundTeamName(anyLong())).thenReturn("Team");
        LineupRatingService lineupRatingService = mock(LineupRatingService.class);

        inject(MatchdayCoordinator.class, coordinator, "liveMatchSimulationService", liveMock);
        inject(MatchdayCoordinator.class, coordinator, "matchPlanService", matchPlanService);
        inject(MatchdayCoordinator.class, coordinator, "matchEventRepository", matchEventRepository);
        inject(MatchdayCoordinator.class, coordinator, "matchRoundSimulator", matchRoundSimulator);
        inject(MatchdayCoordinator.class, coordinator, "lineupRatingService", lineupRatingService);
        inject(MatchdayCoordinator.class, coordinator, "matchSimulationService", mock(MatchSimulationService.class));
        inject(MatchdayCoordinator.class, coordinator, "matchStatsService", mock(MatchStatsService.class));
        inject(MatchdayCoordinator.class, coordinator, "teamPostMatchService", mock(TeamPostMatchService.class));
        inject(MatchdayCoordinator.class, coordinator, "europeanCoefficientService", mock(EuropeanCoefficientService.class));
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoDetailRepository", mock(CompetitionTeamInfoDetailRepository.class));
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoMatchRepository", mock(CompetitionTeamInfoMatchRepository.class));
        inject(MatchdayCoordinator.class, coordinator, "userContext", mock(UserContext.class));

        // 3) Commit.
        Map<String, Object> result = coordinator.finalizeInteractiveLiveMatch(LIVE_KEY);

        // Finding 1: the committed score is the canonical one, NOT the rescore (99-98).
        assertEquals(canonicalHome, result.get("homeScore"));
        assertEquals(canonicalAway, result.get("awayScore"));
        assertNotEquals(99, result.get("homeScore"));

        // 4) The canonical plan is finalized (COMPLETED → COMMITTED)...
        verify(matchPlanService).finishLivePlan(FIXTURE_KEY);
        verify(matchPlanService).markCommitted(FIXTURE_KEY);

        // ...and the Scorer leaderboard is projected from the canonical events (8-arg tally
        // overload), never the legacy RNG 7-arg overload.
        verify(lineupRatingService).getScorersForTeam(eq(HOME_TEAM), eq(AWAY_TEAM), anyInt(), anyInt(),
                any(), eq(COMP), anyInt(), any(Map.class));
        verify(lineupRatingService).getScorersForTeam(eq(AWAY_TEAM), eq(HOME_TEAM), anyInt(), anyInt(),
                any(), eq(COMP), anyInt(), any(Map.class));
        verify(lineupRatingService, never()).getScorersForTeam(anyLong(), anyLong(), anyInt(), anyInt(),
                any(), anyLong(), anyInt());
    }

    @Test
    void rollbackAfterDeferredPersistence_resetsInMemoryFlags_soRetryDoesNotSkipArtifacts() throws Exception {
        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = realLiveService.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 1,
                false, matchup, 2, 1, MATCH_ROW, "442", "442");
        session.setDeferredContext(100.0, 80.0, "442", "442", null, null,
                false, 0, 0L, 0);
        session.advanceUntilAndSnapshot(session.getTotalMinutes());

        MatchdayCoordinator coordinator = new MatchdayCoordinator();
        LiveMatchSimulationService liveMock = mock(LiveMatchSimulationService.class);
        when(liveMock.getSession(LIVE_KEY)).thenReturn(session);
        when(liveMock.resolveCommitOutcome(session)).thenReturn(
                new LiveMatchSimulationService.CommitOutcome(2, 1, 100.0, 80.0, null, null, false));
        MatchEventRepository events = mock(MatchEventRepository.class);
        when(events.findByFixtureKey(FIXTURE_KEY)).thenReturn(List.of(
                goalEvent(105L, HOME_TEAM), goalEvent(106L, HOME_TEAM), goalEvent(205L, AWAY_TEAM)));
        LineupRatingService ratings = mock(LineupRatingService.class);
        doThrow(new IllegalStateException("late DB failure")).when(ratings)
                .getScorersForTeam(anyLong(), anyLong(), anyInt(), anyInt(), any(),
                        anyLong(), anyInt(), any(Map.class));

        inject(MatchdayCoordinator.class, coordinator, "liveMatchSimulationService", liveMock);
        inject(MatchdayCoordinator.class, coordinator, "matchPlanService", matchPlanService);
        inject(MatchdayCoordinator.class, coordinator, "matchEventRepository", events);
        inject(MatchdayCoordinator.class, coordinator, "matchRoundSimulator", mock(MatchRoundSimulator.class));
        inject(MatchdayCoordinator.class, coordinator, "lineupRatingService", ratings);
        inject(MatchdayCoordinator.class, coordinator, "matchSimulationService", mock(MatchSimulationService.class));
        inject(MatchdayCoordinator.class, coordinator, "matchStatsService", mock(MatchStatsService.class));
        inject(MatchdayCoordinator.class, coordinator, "teamPostMatchService", mock(TeamPostMatchService.class));
        inject(MatchdayCoordinator.class, coordinator, "europeanCoefficientService", mock(EuropeanCoefficientService.class));
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoDetailRepository", mock(CompetitionTeamInfoDetailRepository.class));
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoMatchRepository", mock(CompetitionTeamInfoMatchRepository.class));
        inject(MatchdayCoordinator.class, coordinator, "userContext", mock(UserContext.class));

        TransactionSynchronizationManager.initSynchronization();
        List<TransactionSynchronization> callbacks;
        try {
            assertThrows(IllegalStateException.class,
                    () -> coordinator.finalizeInteractiveLiveMatch(LIVE_KEY));
            assertTrue(session.deferredArtifactsPersisted,
                    "the failure happened after the in-memory persistence flag changed");
            callbacks = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertFalse(session.deferredArtifactsPersisted,
                "rollback callback restores retryability of deferred events/fitness");
        assertFalse(session.isCommitted(), "a rolled-back commit is not exposed as committed");
    }

    @Test
    void canonicalKnockoutShootout_derivesWinnerFromPlan_withoutRerollingTie() throws Exception {
        // Blocker #3: a single-leg knockout tied 1-1 at 90', whose ET (0-0) + shootout (4-2)
        // were decided BEFORE kickoff and played live to 120'. The plan carries the shootout;
        // /commit must derive the winner from the plan (HOME on penalties) WITHOUT re-rolling
        // the tie via KnockoutTieResolver (which is not even injected here — a re-roll NPEs).
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        // Duration 120 (extra time played) + a 4-2 shootout; goals at 30' each → 1-1 at 90',
        // no ET goal → still 1-1, decided on penalties.
        LivePlanSnapshot snap = new LivePlanSnapshot(FIXTURE_KEY, 12345L, HOME_TEAM, AWAY_TEAM,
                com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 120, 4, 2,
                List.of(slot(0, HOME_TEAM, 30), slot(1, AWAY_TEAM, 30)),
                participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot(FIXTURE_KEY)).thenReturn(java.util.Optional.of(snap));

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = realLiveService.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 1,
                false, matchup, 1, 1, MATCH_ROW, "442", "442", 0, 0, 4, 2);
        // Single-leg knockout deferred context (legNumber 0, tieId 0).
        session.setDeferredContext(100.0, 80.0, "442", "442", null, null, true, 0, 0L, 0);
        session.advanceUntilAndSnapshot(session.getTotalMinutes());
        assertEquals(1, session.getHomeScore());
        assertEquals(1, session.getAwayScore());

        MatchdayCoordinator coordinator = new MatchdayCoordinator();
        LiveMatchSimulationService liveMock = mock(LiveMatchSimulationService.class);
        when(liveMock.getSession(LIVE_KEY)).thenReturn(session);
        when(liveMock.resolveCommitOutcome(session)).thenReturn(
                new LiveMatchSimulationService.CommitOutcome(1, 1, 100.0, 80.0, null, null, false));

        MatchEventRepository matchEventRepository = mock(MatchEventRepository.class);
        when(matchEventRepository.findByFixtureKey(FIXTURE_KEY)).thenReturn(List.of(
                goalEvent(105L, HOME_TEAM), goalEvent(205L, AWAY_TEAM)));
        MatchRoundSimulator matchRoundSimulator = mock(MatchRoundSimulator.class);
        when(matchRoundSimulator.roundTeamName(anyLong())).thenReturn("Team");
        GameStateService gameStateService = mock(GameStateService.class);
        when(gameStateService.getCupCompetitionIdsCached()).thenReturn(Collections.emptySet());
        CompetitionTeamInfoRepository competitionTeamInfoRepository = mock(CompetitionTeamInfoRepository.class);

        inject(MatchdayCoordinator.class, coordinator, "liveMatchSimulationService", liveMock);
        inject(MatchdayCoordinator.class, coordinator, "matchPlanService", matchPlanService);
        inject(MatchdayCoordinator.class, coordinator, "matchEventRepository", matchEventRepository);
        inject(MatchdayCoordinator.class, coordinator, "matchRoundSimulator", matchRoundSimulator);
        inject(MatchdayCoordinator.class, coordinator, "lineupRatingService", mock(LineupRatingService.class));
        inject(MatchdayCoordinator.class, coordinator, "matchSimulationService", mock(MatchSimulationService.class));
        inject(MatchdayCoordinator.class, coordinator, "matchStatsService", mock(MatchStatsService.class));
        inject(MatchdayCoordinator.class, coordinator, "teamPostMatchService", mock(TeamPostMatchService.class));
        inject(MatchdayCoordinator.class, coordinator, "europeanCoefficientService", mock(EuropeanCoefficientService.class));
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoDetailRepository", mock(CompetitionTeamInfoDetailRepository.class));
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoMatchRepository", mock(CompetitionTeamInfoMatchRepository.class));
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoRepository", competitionTeamInfoRepository);
        inject(MatchdayCoordinator.class, coordinator, "cupBracketService", mock(CupBracketService.class));
        inject(MatchdayCoordinator.class, coordinator, "gameStateService", gameStateService);
        inject(MatchdayCoordinator.class, coordinator, "userContext", mock(UserContext.class));

        Map<String, Object> result = coordinator.finalizeInteractiveLiveMatch(LIVE_KEY);

        // Winner + shootout derived from the plan — no tie re-roll.
        assertEquals(HOME_TEAM, result.get("winnerTeamId"), "HOME wins the pre-decided shootout");
        assertEquals("PENALTIES", result.get("decidedBy"));
        assertEquals(4, result.get("penaltyTeam1Score"));
        assertEquals(2, result.get("penaltyTeam2Score"));
        // The football score stays 1-1 (shootout kicks are not goals).
        assertEquals(1, result.get("homeScore"));
        assertEquals(1, result.get("awayScore"));
        // Plan finalized; NO extra-time append (ET already in the played plan).
        verify(matchPlanService).finishLivePlan(FIXTURE_KEY);
        verify(matchPlanService).markCommitted(FIXTURE_KEY);
        verify(matchPlanService, never()).appendExtraTimeAndFinalize(anyString(), anyLong(), anyInt(),
                anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
        // Winner propagated into the next round.
        verify(competitionTeamInfoRepository).save(any());
    }

    @Test
    void canonicalKnockoutSecondLeg_aggregatesWithLeg1_andDecidesFromPlanShootout() throws Exception {
        // Two-leg tie, leg 2 (canonical): leg 1 ended 2-1 to the side that hosted it (= side A,
        // here team2). Leg 2 is 1-0 to the home side (team1 = side B) → aggregate 2-2, decided
        // on a pre-decided shootout the HOME side wins 5-4. /commit must aggregate with leg 1
        // and read the shootout from the plan — no tie re-roll.
        long tieId = 999L;
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        LivePlanSnapshot snap = new LivePlanSnapshot(FIXTURE_KEY, 12345L, HOME_TEAM, AWAY_TEAM,
                com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 120, 5, 4,
                List.of(slot(0, HOME_TEAM, 30)), // leg 2: 1-0 to home at 90'
                participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot(FIXTURE_KEY)).thenReturn(java.util.Optional.of(snap));

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = realLiveService.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 1,
                false, matchup, 1, 0, MATCH_ROW, "442", "442", 0, 0, 5, 4);
        session.setDeferredContext(100.0, 80.0, "442", "442", null, null, true, 2, tieId, 3);
        session.advanceUntilAndSnapshot(session.getTotalMinutes());
        assertEquals(1, session.getHomeScore());
        assertEquals(0, session.getAwayScore());

        MatchdayCoordinator coordinator = new MatchdayCoordinator();
        LiveMatchSimulationService liveMock = mock(LiveMatchSimulationService.class);
        when(liveMock.getSession(LIVE_KEY)).thenReturn(session);
        when(liveMock.resolveCommitOutcome(session)).thenReturn(
                new LiveMatchSimulationService.CommitOutcome(1, 0, 100.0, 80.0, null, null, false));

        MatchEventRepository matchEventRepository = mock(MatchEventRepository.class);
        when(matchEventRepository.findByFixtureKey(FIXTURE_KEY)).thenReturn(List.of(goalEvent(105L, HOME_TEAM)));
        MatchRoundSimulator matchRoundSimulator = mock(MatchRoundSimulator.class);
        when(matchRoundSimulator.roundTeamName(anyLong())).thenReturn("Team");
        GameStateService gameStateService = mock(GameStateService.class);
        when(gameStateService.getCupCompetitionIdsCached()).thenReturn(Collections.emptySet());
        CompetitionTeamInfoRepository competitionTeamInfoRepository = mock(CompetitionTeamInfoRepository.class);
        CompetitionTeamInfoMatchRepository fixtureRepo = mock(CompetitionTeamInfoMatchRepository.class);
        // Leg 1 ended 2-1 to the leg-1 host (side A = team2). team1Score is the leg-1 host's.
        com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch leg1 =
                new com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch();
        leg1.setTeam1Score(2);
        leg1.setTeam2Score(1);
        com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch leg2 =
                new com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch();
        when(fixtureRepo.findByTieIdAndLegNumber(tieId, 1)).thenReturn(java.util.Optional.of(leg1));
        when(fixtureRepo.findByTieIdAndLegNumber(tieId, 2)).thenReturn(java.util.Optional.of(leg2));
        when(fixtureRepo.findPlayedFixture(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(Collections.emptyList());

        inject(MatchdayCoordinator.class, coordinator, "liveMatchSimulationService", liveMock);
        inject(MatchdayCoordinator.class, coordinator, "matchPlanService", matchPlanService);
        inject(MatchdayCoordinator.class, coordinator, "matchEventRepository", matchEventRepository);
        inject(MatchdayCoordinator.class, coordinator, "matchRoundSimulator", matchRoundSimulator);
        inject(MatchdayCoordinator.class, coordinator, "lineupRatingService", mock(LineupRatingService.class));
        inject(MatchdayCoordinator.class, coordinator, "matchSimulationService", mock(MatchSimulationService.class));
        inject(MatchdayCoordinator.class, coordinator, "matchStatsService", mock(MatchStatsService.class));
        inject(MatchdayCoordinator.class, coordinator, "teamPostMatchService", mock(TeamPostMatchService.class));
        inject(MatchdayCoordinator.class, coordinator, "europeanCoefficientService", mock(EuropeanCoefficientService.class));
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoDetailRepository", mock(CompetitionTeamInfoDetailRepository.class));
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoMatchRepository", fixtureRepo);
        inject(MatchdayCoordinator.class, coordinator, "competitionTeamInfoRepository", competitionTeamInfoRepository);
        inject(MatchdayCoordinator.class, coordinator, "cupBracketService", mock(CupBracketService.class));
        inject(MatchdayCoordinator.class, coordinator, "gameStateService", gameStateService);
        inject(MatchdayCoordinator.class, coordinator, "userContext", mock(UserContext.class));

        Map<String, Object> result = coordinator.finalizeInteractiveLiveMatch(LIVE_KEY);

        assertEquals(HOME_TEAM, result.get("winnerTeamId"), "HOME (side B) wins the aggregate shootout");
        assertEquals("PENALTIES", result.get("decidedBy"));
        assertEquals(2, result.get("aggregateTeam1Score"), "home aggregate = leg1 1 + leg2 1");
        assertEquals(2, result.get("aggregateTeam2Score"), "away aggregate = leg1 2 + leg2 0");
        assertEquals(5, result.get("penaltyTeam1Score"));
        assertEquals(4, result.get("penaltyTeam2Score"));
        verify(matchPlanService).finishLivePlan(FIXTURE_KEY);
        verify(matchPlanService, never()).appendExtraTimeAndFinalize(anyString(), anyLong(), anyInt(),
                anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
        verify(competitionTeamInfoRepository).save(any());
    }

    // ---------------- fixtures ----------------

    private LivePlanSnapshot.SlotView slot(int index, long teamId, int minute) {
        return new LivePlanSnapshot.SlotView(index, teamId, minute, GoalPhase.REGULAR_TIME,
                "OPEN_PLAY", false, null, null);
    }

    private LivePlanSnapshot.ParticipantView pv(long teamId, int index, Contributor c) {
        return new LivePlanSnapshot.ParticipantView(teamId, index, index < 11, c);
    }

    private Contributor contrib(long id) {
        return new Contributor(id, "P" + id, "MC", 15.0, 12, 12, 12, 100.0, false, false);
    }

    private com.footballmanagergamesimulator.matchplan.Lineup kickoffXi(long base) {
        List<Contributor> xi = new ArrayList<>();
        for (long i = 0; i < 11; i++) xi.add(contrib(base + i));
        return new com.footballmanagergamesimulator.matchplan.Lineup(xi, List.of());
    }

    private MatchEvent goalEvent(long scorerId, long teamId) {
        MatchEvent e = new MatchEvent();
        e.setEventType("goal");
        e.setPlayerId(scorerId);
        e.setPlayerName("P" + scorerId);
        e.setTeamId(teamId);
        return e;
    }

    private List<Human> buildSquad(long baseId) {
        List<Human> squad = new ArrayList<>();
        squad.add(human(baseId, "GK", 70));
        String[] outfield = {"DC", "DC", "DL", "DR", "MC", "MC", "ML", "MR", "AMC", "ST", "ST"};
        for (int i = 0; i < outfield.length; i++) squad.add(human(baseId + 1 + i, outfield[i], 70 - i % 5));
        squad.add(human(baseId + 12, "ST", 55));
        return squad;
    }

    private static Human human(long id, String position, double rating) {
        Human h = new Human();
        h.setId(id);
        h.setName(position + "_" + id);
        h.setPosition(position);
        h.setRating(rating);
        h.setRetired(false);
        h.setFitness(100.0);
        return h;
    }

    private static void inject(Class<?> type, Object target, String fieldName, Object value) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
