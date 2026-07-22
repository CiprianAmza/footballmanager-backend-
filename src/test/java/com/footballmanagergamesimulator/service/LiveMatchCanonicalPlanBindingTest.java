package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.GoalPhase;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.LivePlanSnapshot;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.TacticalScoreService.Matchup;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Session-side proof of the canonical-plan LIVE wiring (feature flag ON): with a
 * prepared plan, the interactive {@link LiveMatchSession} takes its kickoff squad
 * snapshot from its OWN chosen eleven, binds the persisted plan, and narrates goals
 * at the plan's fixed regular-time minutes/sides — so the watched scoreline is the
 * plan's, not an independent {@code pickRandomMinutes} schedule. The plan's
 * persistence is covered separately by {@code MatchPlan*Test} (@DataJpaTest); here
 * {@link MatchPlanService} is mocked to isolate the session behavior.
 */
class LiveMatchCanonicalPlanBindingTest {

    private LiveMatchSimulationService service;
    private TacticalScoreService tacticalScoreService;
    private MatchEngineConfig engineConfig;
    private MatchPlanService matchPlanService;
    private GoalAnimationService goalAnimationService;
    private com.footballmanagergamesimulator.repository.LiveCommitContextRepository liveCommitContextRepository;
    private MatchAnimationRecipeRepository matchAnimationRecipeRepository;

    private static final long HOME_TEAM = 1L;
    private static final long AWAY_TEAM = 2L;
    private static final long COMP = 10L;
    private static final long MATCH_ROW = 999L;

    @BeforeEach
    void setUp() throws Exception {
        engineConfig = new MatchEngineConfig();
        engineConfig.getTacticalModel().setEnabled(true);
        engineConfig.getMatchPlan().setEnabled(true); // flag ON

        service = new LiveMatchSimulationService();
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
        MatchEventRepository matchEventRepository = mock(MatchEventRepository.class);
        goalAnimationService = mock(GoalAnimationService.class);
        matchPlanService = mock(MatchPlanService.class);

        injectService("humanRepository", humanRepository);
        injectService("teamRepository", teamRepository);
        injectService("competitionRepository", competitionRepository);
        injectService("playerSkillsRepository", playerSkillsRepository);
        injectService("matchEventRepository", matchEventRepository);
        injectService("goalAnimationService", goalAnimationService);
        injectService("engineConfig", engineConfig);
        injectService("matchPlanService", matchPlanService);
        liveCommitContextRepository = mock(com.footballmanagergamesimulator.repository.LiveCommitContextRepository.class);
        injectService("liveCommitContextRepository", liveCommitContextRepository);
        matchAnimationRecipeRepository = mock(MatchAnimationRecipeRepository.class);
        injectService("matchAnimationRecipeRepository", matchAnimationRecipeRepository);
        injectService("objectMapper", new ObjectMapper());

        tacticalScoreService = new TacticalScoreService();
        injectField(TacticalScoreService.class, tacticalScoreService, "engineConfig", engineConfig);

        when(humanRepository.findAllByTeamIdAndTypeId(HOME_TEAM, 1L)).thenReturn(buildSquad(100L));
        when(humanRepository.findAllByTeamIdAndTypeId(AWAY_TEAM, 1L)).thenReturn(buildSquad(200L));
        when(teamRepository.findNameById(HOME_TEAM)).thenReturn("Home FC");
        when(teamRepository.findNameById(AWAY_TEAM)).thenReturn("Away FC");
        when(competitionRepository.findNameById(COMP)).thenReturn("Test League");
        when(playerSkillsRepository.findAllByPlayerIdIn(any())).thenReturn(Collections.emptyList());
        // The mocked service gates on its own isEnabled(); default the flag ON (a test
        // toggles it off). prepareLivePlan is a no-op here — persistence is covered by
        // the @DataJpaTest suites.
        when(matchPlanService.isEnabled()).thenAnswer(inv -> engineConfig.getMatchPlan().isEnabled());
        // Authoritative kickoff XI from the adapter (ids within each squad so the session adopts it).
        when(matchPlanService.buildKickoffLineups(anyString(), anyLong(), anyInt(), anyInt(),
                anyLong(), anyLong(), any(), any()))
                .thenReturn(new MatchPlanService.KickoffLineups(kickoffXi(100), kickoffXi(200)));
    }

    private Lineup kickoffXi(long base) {
        List<Contributor> xi = new ArrayList<>();
        for (long i = 0; i < 11; i++) xi.add(contrib(base + i));
        return new Lineup(xi, List.of());
    }

    @Test
    void canonicalPlan_bound_narratesGoalsAtPlanMinutesAndSides() {
        int targetHome = 2, targetAway = 1;
        // The plan schedule: home goals at 20' and 60', away goal at 40'.
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(
                        slot(0, HOME_TEAM, 20), slot(1, HOME_TEAM, 60), slot(2, AWAY_TEAM, 40)),
                participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> {
                    List<Contributor> onPitch = inv.getArgument(5);
                    return List.of(goalEvent(onPitch.isEmpty() ? 0L : onPitch.get(0).playerId()));
                });

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                false, matchup, targetHome, targetAway, MATCH_ROW, "4-4-2", "4-4-2");

        assertTrue(session.isCanonicalPlanBound(), "the persisted plan is bound to the session");
        assertEquals("CTIM:" + MATCH_ROW, session.getCanonicalFixtureKey());

        // The kickoff snapshot was taken from the session's own eleven (11 starters).
        ArgumentCaptor<Lineup> homeXi = ArgumentCaptor.forClass(Lineup.class);
        verify(matchPlanService).prepareLivePlan(eq("CTIM:" + MATCH_ROW), eq(COMP), eq(1), eq(7),
                eq(HOME_TEAM), eq(AWAY_TEAM), homeXi.capture(), any(Lineup.class),
                eq(targetHome), eq(targetAway), anyInt(), anyInt(), anyInt(), anyInt());
        assertEquals(11, homeXi.getValue().getStartingXI().size(), "kickoff XI = the session's on-pitch eleven");

        // Advance to full time: goals land at the plan's minutes → final score == plan.
        session.advanceUntilAndSnapshot(session.getTotalMinutes());
        assertTrue(session.isFinished());
        assertEquals(targetHome, session.getHomeScore());
        assertEquals(targetAway, session.getAwayScore());

        // The goals were narrated at the plan's fixed minutes/sides.
        var goals = session.snapshot().getTimeline().stream()
                .filter(m -> "goal".equals(m.getEventType())).toList();
        List<Integer> homeGoalMinutes = goals.stream()
                .filter(g -> g.getTeamId() == HOME_TEAM).map(g -> g.getMinute()).sorted().toList();
        List<Integer> awayGoalMinutes = goals.stream()
                .filter(g -> g.getTeamId() == AWAY_TEAM).map(g -> g.getMinute()).sorted().toList();
        assertEquals(List.of(20, 60), homeGoalMinutes, "home goals at the plan's minutes");
        assertEquals(List.of(40), awayGoalMinutes, "away goal at the plan's minute");
    }

    @Test
    void canonicalGoal_displayedScorerEqualsResolvedPersistedScorer() {
        // Participants cover the whole squad so canonicalOnPitch can match on-pitch ids.
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));

        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(slot(0, HOME_TEAM, 20), slot(1, HOME_TEAM, 60), slot(2, AWAY_TEAM, 40)),
                participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));
        // The service resolves slot N to scorer 5000+N and persists that event; the
        // session must DISPLAY exactly that scorer.
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> {
                    int slotIndex = inv.getArgument(4);
                    return List.of(goalEvent(5000 + slotIndex));
                });

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                false, matchup, 2, 1, MATCH_ROW, "4-4-2", "4-4-2");
        session.advanceUntilAndSnapshot(session.getTotalMinutes());

        var goals = session.snapshot().getTimeline().stream()
                .filter(m -> "goal".equals(m.getEventType())).toList();
        // Slot 0 → minute 20 → scorer 5000; slot 1 → minute 60 → 5001; slot 2 → minute 40 → 5002.
        assertEquals(5000L, goalScorerAt(goals, 20), "displayed scorer == resolved/persisted scorer (slot 0)");
        assertEquals(5001L, goalScorerAt(goals, 60), "displayed scorer == resolved/persisted scorer (slot 1)");
        assertEquals(5002L, goalScorerAt(goals, 40), "displayed scorer == resolved/persisted scorer (slot 2)");
    }

    @Test
    void manualSub_recordedCanonically_andLaterGoalResolvesAgainstPostSubOnPitch() {
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(slot(0, HOME_TEAM, 20), slot(1, HOME_TEAM, 60), slot(2, AWAY_TEAM, 40)),
                participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> List.of(goalEvent(5000 + (int) inv.getArgument(4))));

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                false, matchup, 2, 1, MATCH_ROW, "4-4-2", "4-4-2");

        // Play to 30', sub the top outfielder (101, on-pitch) off for the bench ST (112),
        // then finish. The minute-20 goal was before the sub; the minute-60 goal after.
        session.advanceUntilAndSnapshot(30);
        session.applyUserSubAtMinuteAndSnapshot(101L, 112L, 30);
        session.advanceUntilAndSnapshot(session.getTotalMinutes());

        // The real substitution is recorded into the canonical timeline.
        verify(matchPlanService).recordLiveSubstitution("CTIM:" + MATCH_ROW, HOME_TEAM, 30, 101L, 112L);

        // The later home goal (slot 1, minute 60) was resolved against the POST-sub
        // on-pitch set: 112 is on, 101 is off.
        ArgumentCaptor<Integer> slotCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<List> onPitchCap = ArgumentCaptor.forClass(List.class);
        verify(matchPlanService, atLeastOnce()).resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(),
                slotCap.capture(), onPitchCap.capture());
        int idx = slotCap.getAllValues().indexOf(1);
        assertTrue(idx >= 0, "slot 1 was resolved");
        @SuppressWarnings("unchecked")
        List<Contributor> onPitch60 = onPitchCap.getAllValues().get(idx);
        Set<Long> ids = onPitch60.stream().map(Contributor::playerId).collect(Collectors.toSet());
        assertTrue(ids.contains(112L), "subbed-on player is on the pitch for the later goal");
        assertFalse(ids.contains(101L), "subbed-off player is not on the pitch for the later goal");
    }

    private long goalScorerAt(List<? extends com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute> goals, int minute) {
        return goals.stream().filter(g -> g.getMinute() == minute)
                .mapToLong(g -> g.getPlayerId()).findFirst().orElseThrow();
    }

    private LivePlanSnapshot.ParticipantView pv(long teamId, int index, Contributor c) {
        return new LivePlanSnapshot.ParticipantView(teamId, index, index < 11, c);
    }

    private Contributor contrib(long id) {
        return new Contributor(id, "P" + id, "MC", 15.0, 12, 12, 12, 100.0, false, false);
    }

    private MatchEvent goalEvent(long scorerId) {
        MatchEvent e = new MatchEvent();
        e.setEventType("goal");
        e.setPlayerId(scorerId);
        e.setPlayerName("P" + scorerId);
        return e;
    }

    @Test
    void twoCanonicalGoalsSameMinute_bothAnimationsKept_orderedBySlotIndex() {
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        // Two home goals at the SAME minute (30), slots 0 and 1, plus one away goal.
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(slot(0, HOME_TEAM, 30), slot(1, HOME_TEAM, 30), slot(2, AWAY_TEAM, 40)),
                participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));
        // Resolve each slot to a real on-pitch player so the animation lookup succeeds.
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> {
                    List<Contributor> onPitch = inv.getArgument(5);
                    return List.of(goalEvent(onPitch.isEmpty() ? 0L : onPitch.get(0).playerId()));
                });
        // Real generator is mocked out; return a fresh animation per call.
        when(goalAnimationService.generate(anyList(), anyList(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyInt(), anyString()))
                .thenAnswer(inv -> new GoalAnimationData());

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                true, matchup, 2, 1, MATCH_ROW, "4-4-2", "4-4-2"); // animations ON
        session.advanceUntilAndSnapshot(session.getTotalMinutes());

        assertEquals(2, session.getHomeScore(), "both same-minute goals count");
        // Canonical goal animations are on the separate ordered boundary (not the legacy map).
        List<GoalAnimationData> at30 = session.snapshot().getCanonicalAnimations().stream()
                .filter(a -> a.getMinute() == 30).toList();
        assertEquals(2, at30.size(), "both goals' animations kept — not overwritten");
        assertEquals(0, at30.get(0).getSlotIndex(), "ordered by slotIndex: slot 0 first");
        assertEquals(1, at30.get(1).getSlotIndex(), "then slot 1");
        assertEquals("CTIM:" + MATCH_ROW, at30.get(0).getFixtureKey(), "animation carries the fixture key");
        assertEquals(GoalAnimationData.GENERATOR_VERSION, at30.get(0).getGeneratorVersion(),
                "animation carries the generator version");
        // Versioned boundary: the legacy minute-keyed map remains a Map (byte-compatible).
        assertTrue(session.snapshot().getGoalAnimations() instanceof java.util.Map,
                "legacy goalAnimations stays a minute-keyed map");

        // Each goal's animation is seeded from the plan seed (derived from fixtureKey)
        // mixed with its slotIndex — distinct per slot even at the same minute, and
        // restart-stable. Cleared after each generation.
        long base = 12345L * 2_000_003L;
        verify(goalAnimationService).setAnimationSeed(base + 0);
        verify(goalAnimationService).setAnimationSeed(base + 1);
        verify(goalAnimationService, atLeast(2)).clearAnimationSeed();
        verify(matchAnimationRecipeRepository, times(3)).save(any());
    }

    @Test
    void canonicalAnimationRecipe_roundTripsExactFrames_andOldVersionIsNeverRegenerated() throws Exception {
        GoalAnimationData animation = new GoalAnimationData();
        animation.setFixtureKey("CTIM:" + MATCH_ROW);
        animation.setSlotIndex(4);
        animation.setMinute(63);
        animation.setGeneratorVersion(1);
        animation.setOutcome("GOAL");
        GoalAnimationData.AnimationFrame frame = new GoalAnimationData.AnimationFrame();
        frame.setBallX(91.25);
        frame.setBallY(44.5);
        frame.setBallCarrierId(105L);
        frame.setPositions(List.of(new double[]{12.5, 30.75}));
        animation.setFrames(List.of(frame));

        when(matchAnimationRecipeRepository.findByFixtureKeyAndSlotIndex("CTIM:" + MATCH_ROW, 4))
                .thenReturn(java.util.Optional.empty());
        ArgumentCaptor<com.footballmanagergamesimulator.matchplan.MatchAnimationRecipe> persisted =
                ArgumentCaptor.forClass(com.footballmanagergamesimulator.matchplan.MatchAnimationRecipe.class);
        service.persistCanonicalAnimation("CTIM:" + MATCH_ROW, 4, animation);
        verify(matchAnimationRecipeRepository).save(persisted.capture());

        var historical = persisted.getValue();
        when(matchAnimationRecipeRepository.findByFixtureKeyOrderByMinuteAscSlotIndexAsc("CTIM:" + MATCH_ROW))
                .thenReturn(List.of(historical));
        GoalAnimationData restored = service.loadCanonicalAnimations("CTIM:" + MATCH_ROW).get(0);
        assertEquals(91.25, restored.getFrames().get(0).getBallX());
        assertArrayEquals(new double[]{12.5, 30.75}, restored.getFrames().get(0).getPositions().get(0));

        // Even if current generator code later moves to v2, fixture+slot is the durable
        // identity. The historical v1 row wins and a second recipe must not be generated.
        animation.setGeneratorVersion(2);
        when(matchAnimationRecipeRepository.findByFixtureKeyAndSlotIndex("CTIM:" + MATCH_ROW, 4))
                .thenReturn(java.util.Optional.of(historical));
        service.persistCanonicalAnimation("CTIM:" + MATCH_ROW, 4, animation);
        verify(matchAnimationRecipeRepository, times(1)).save(any());
    }

    @Test
    void slotPersistenceFailure_failsAdvance_noPhantomGoal_thenRetrySucceedsOnce() {
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(slot(0, HOME_TEAM, 20)), participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));
        // First resolution fails (DB down), then succeeds on retry.
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenThrow(new RuntimeException("db down"))
                .thenAnswer(inv -> {
                    List<Contributor> onPitch = inv.getArgument(5);
                    return List.of(goalEvent(onPitch.get(0).playerId()));
                });

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                false, matchup, 1, 0, MATCH_ROW, "4-4-2", "4-4-2");

        // The failed persistence fails the advance and mutates NOTHING (no phantom goal).
        assertThrows(RuntimeException.class,
                () -> session.advanceUntilAndSnapshot(session.getTotalMinutes()));
        assertEquals(0, session.getHomeScore(), "no score mutation on failure");
        assertTrue(session.snapshot().getTimeline().stream().noneMatch(m -> "goal".equals(m.getEventType())),
                "no phantom goal shown");

        // Retry: the same slot resolves once, the goal lands exactly once (no double-count).
        session.advanceUntilAndSnapshot(session.getTotalMinutes());
        assertEquals(1, session.getHomeScore());
        assertEquals(1, session.snapshot().getTimeline().stream()
                .filter(m -> "goal".equals(m.getEventType())).count());
    }

    @Test
    void manualSub_persistenceFailure_rejectsSub_withNoOnPitchMutation() {
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(), participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));
        // Recording the substitution fails.
        doThrow(new RuntimeException("db down")).when(matchPlanService)
                .recordLiveSubstitution(anyString(), anyLong(), anyInt(), anyLong(), anyLong());

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                false, matchup, 0, 0, MATCH_ROW, "4-4-2", "4-4-2");
        session.advanceUntilAndSnapshot(30);

        // The atomic persist fails → the substitution is rejected and nothing is mutated.
        assertThrows(LiveMatchSimulationService.InvalidSubstitutionException.class,
                () -> session.applyUserSubAtMinuteAndSnapshot(101L, 112L, 30));
        assertTrue(session.snapshot().getTimeline().stream()
                .noneMatch(m -> "substitution".equals(m.getEventType())), "no sub applied");
    }

    @Test
    void penaltySlot_animatedAsPenalty_notOpenPlay() {
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(slot(0, HOME_TEAM, 30, "PENALTY")), participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> List.of(goalEvent(((List<Contributor>) inv.getArgument(5)).get(0).playerId())));
        when(goalAnimationService.generatePenalty(anyList(), anyList(), any(), anyLong(), anyLong(),
                anyLong(), anyInt(), anyBoolean())).thenAnswer(inv -> new GoalAnimationData());

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                true, matchup, 1, 0, MATCH_ROW, "4-4-2", "4-4-2");
        session.advanceUntilAndSnapshot(session.getTotalMinutes());

        assertEquals(1, session.getHomeScore(), "penalty goal scored");
        // The canonical PENALTY goal (minute 30) is animated via the penalty generator.
        // Capturing the minute proves it is THIS slot, not just a random cosmetic penalty.
        ArgumentCaptor<Integer> minCap = ArgumentCaptor.forClass(Integer.class);
        verify(goalAnimationService, atLeastOnce()).generatePenalty(anyList(), anyList(), any(),
                anyLong(), anyLong(), anyLong(), minCap.capture(), anyBoolean());
        assertTrue(minCap.getAllValues().contains(30),
                "the canonical penalty at minute 30 is animated as a penalty");
    }

    @Test
    void session_adoptsAdapterXi_preservingDesignatedTakers() {
        // The adapter's authoritative XI includes a designated penalty taker (105).
        List<Contributor> homeStarters = new ArrayList<>();
        for (long i = 100; i <= 110; i++) {
            boolean penTaker = (i == 105);
            homeStarters.add(new Contributor(i, "P" + i, "MC", 15.0, 12, 12, 12, 100.0, penTaker, false));
        }
        when(matchPlanService.buildKickoffLineups(anyString(), anyLong(), anyInt(), anyInt(),
                anyLong(), anyLong(), any(), any()))
                .thenReturn(new MatchPlanService.KickoffLineups(new Lineup(homeStarters, List.of()), kickoffXi(200)));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(), List.of(), List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        service.createInteractiveSession(HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                false, matchup, 0, 0, MATCH_ROW, "4-4-2", "4-4-2");

        // The prepared kickoff snapshot's XI is the adapter's, and the taker flag survives.
        ArgumentCaptor<com.footballmanagergamesimulator.matchplan.Lineup> homeCap =
                ArgumentCaptor.forClass(com.footballmanagergamesimulator.matchplan.Lineup.class);
        verify(matchPlanService).prepareLivePlan(anyString(), anyLong(), anyInt(), anyInt(),
                anyLong(), anyLong(), homeCap.capture(), any(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt());
        List<Contributor> starters = homeCap.getValue().getStartingXI();
        assertEquals(11, starters.size());
        assertEquals(List.of(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L, 109L, 110L),
                starters.stream().map(Contributor::playerId).toList(), "the adapter's XI, not highest-rated");
        assertTrue(starters.stream().anyMatch(c -> c.playerId() == 105L && c.designatedPenaltyTaker()),
                "the designated penalty taker is preserved in the snapshot");
    }

    @Test
    void coldRecovery_recreatesSession_reproducesScore_andReplaysSubs() {
        // Persisted state after a "restart": participants (XI 100-110 + bench 111,112),
        // three goal slots (home 20',60'; away 40'), and one recorded sub (105 -> 112 @30').
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 110; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), true, contrib(id)));
        for (long id = 111; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), false, contrib(id)));
        for (long id = 200; id <= 210; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), true, contrib(id)));
        for (long id = 211; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), false, contrib(id)));
        List<LivePlanSnapshot.SlotView> slots = List.of(
                slot(0, HOME_TEAM, 20), slot(1, HOME_TEAM, 60), slot(2, AWAY_TEAM, 40));
        List<LivePlanSnapshot.SubView> subs = List.of(
                new LivePlanSnapshot.SubView(HOME_TEAM, 0, 30, 105L, 112L));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.IN_PROGRESS, 90, -1, -1,
                slots, participants, subs);
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> List.of(goalEvent(((List<Contributor>) inv.getArgument(5)).get(0).playerId())));

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        // Cold recovery from persisted state (no prior in-memory session).
        LiveMatchSession recovered = service.recoverCanonicalSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7, false, matchup, MATCH_ROW);
        assertNotNull(recovered, "a non-committed plan is recoverable");
        recovered.advanceUntilAndSnapshot(recovered.getTotalMinutes());

        // The score is reproduced from the persisted slots (2 home + 1 away).
        assertEquals(2, recovered.getHomeScore());
        assertEquals(1, recovered.getAwayScore());
        // The recorded substitution replayed at its minute.
        assertTrue(recovered.snapshot().getTimeline().stream()
                        .anyMatch(m -> "substitution".equals(m.getEventType()) && m.getPlayerId() == 105L),
                "the recorded sub (105 off) replays on recovery");
    }

    private LivePlanSnapshot.ParticipantView pv(long teamId, int index, boolean starter, Contributor c) {
        return new LivePlanSnapshot.ParticipantView(teamId, index, starter, c);
    }

    @Test
    void getSessionOrRecover_restoresDeferredCommitContext_soCommitIsSafeAfterRestart() {
        String liveKey = COMP + "_1_7_" + HOME_TEAM + "_" + AWAY_TEAM;
        var ctx = new com.footballmanagergamesimulator.matchplan.LiveCommitContext(
                liveKey, MATCH_ROW, "451", "352", 100.0, 80.0, true, 2, 999L, 5);
        when(liveCommitContextRepository.findByLiveKey(liveKey)).thenReturn(java.util.Optional.of(ctx));

        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 110; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), true, contrib(id)));
        for (long id = 200; id <= 210; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), true, contrib(id)));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.IN_PROGRESS, 90, -1, -1,
                List.of(slot(0, HOME_TEAM, 20)), participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));

        LiveMatchSession recovered = service.getSessionOrRecover(liveKey);

        assertNotNull(recovered, "recovered via the persisted commit context");
        // The deferred commit context is restored, so a /commit after restart is safe.
        assertEquals("451", recovered.getDeferredTactic1());
        assertEquals("352", recovered.getDeferredTactic2());
        assertTrue(recovered.isDeferredKnockout());
        assertEquals(2, recovered.getDeferredLegNumber(), "two-leg tie recovered (not just legNumber 0)");
        assertEquals(999L, recovered.getDeferredTieId());
        assertEquals(5, recovered.getDeferredMatchIndex());
    }

    @Test
    void commitRecovery_rejectsContextFreeFallbackAfterRestart() {
        String liveKey = COMP + "_1_7_" + HOME_TEAM + "_" + AWAY_TEAM;
        when(liveCommitContextRepository.findByLiveKey(liveKey))
                .thenReturn(java.util.Optional.empty());

        assertNull(service.getSessionOrRecoverForCommit(liveKey),
                "commit must not use a fallback that lacks tactics and knockout metadata");
        verify(matchPlanService, never()).loadLivePlanSnapshot(anyString());
    }

    @Test
    void coldRecovery_resumesAtCheckpointMinute_notReplayFromZero() {
        String liveKey = COMP + "_1_7_" + HOME_TEAM + "_" + AWAY_TEAM;
        var ctx = new com.footballmanagergamesimulator.matchplan.LiveCommitContext(
                liveKey, MATCH_ROW, "442", "442", 100.0, 80.0, false, 0, 0L, 0);
        ctx.setCheckpointMinute(50);
        ctx.setRedCardPlayerIds("103"); // sent off before the crash
        when(liveCommitContextRepository.findByLiveKey(liveKey)).thenReturn(java.util.Optional.of(ctx));

        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 110; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), true, contrib(id)));
        for (long id = 200; id <= 210; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), true, contrib(id)));
        // home@20 RESOLVED (before checkpoint), home@60 UNRESOLVED (after checkpoint).
        List<LivePlanSnapshot.SlotView> slots = List.of(
                new LivePlanSnapshot.SlotView(0, HOME_TEAM, 20, GoalPhase.REGULAR_TIME, "OPEN_PLAY", true, 105L, null),
                new LivePlanSnapshot.SlotView(1, HOME_TEAM, 60, GoalPhase.REGULAR_TIME, "OPEN_PLAY", false, null, null));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.IN_PROGRESS, 90, -1, -1,
                slots, participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));

        LiveMatchSession recovered = service.getSessionOrRecover(liveKey);

        assertNotNull(recovered);
        // RESUME at the crash minute, with the pre-checkpoint goal already scored — NOT replayed
        // from 0 (which would re-roll the red card / AI subs and change future candidates).
        assertEquals(50, recovered.currentMinute, "resumes at the checkpoint minute");
        assertEquals(1, recovered.getHomeScore(), "the pre-checkpoint goal is restored, not replayed");
        assertEquals(0, recovered.getAwayScore());
        assertFalse(recovered.matchStates.get(103L).isOnPitch, "the red-carded player stays off the pitch");
    }

    @Test
    void fullCheckpoint_afterManualSub_restoresExactStateAndInheritedRole() throws Exception {
        String liveKey = COMP + "_1_7_" + HOME_TEAM + "_" + AWAY_TEAM;
        var initialContext = new com.footballmanagergamesimulator.matchplan.LiveCommitContext(
                liveKey, MATCH_ROW, "442", "442", 100.0, 80.0, false, 0, 0L, 0);
        AtomicReference<com.footballmanagergamesimulator.matchplan.LiveCommitContext> context =
                new AtomicReference<>(initialContext);
        when(liveCommitContextRepository.findByLiveKey(liveKey))
                .thenAnswer(inv -> java.util.Optional.ofNullable(context.get()));
        when(liveCommitContextRepository.save(any()))
                .thenAnswer(inv -> {
                    var saved = (com.footballmanagergamesimulator.matchplan.LiveCommitContext) inv.getArgument(0);
                    context.set(saved);
                    return saved;
                });

        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) {
            String position = id == 101 ? "DC" : id == 112 ? "ST" : "MC";
            Contributor contributor = new Contributor(id, "P" + id, position,
                    15.0, 12, 12, 12, 100.0, false, false);
            participants.add(pv(HOME_TEAM, (int) (id - 100), id <= 110, contributor));
        }
        for (long id = 200; id <= 212; id++) {
            participants.add(pv(AWAY_TEAM, (int) (id - 200), id <= 210, contrib(id)));
        }
        LivePlanSnapshot snapshot = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.IN_PROGRESS,
                90, -1, -1,
                List.of(slot(0, HOME_TEAM, 20), slot(1, HOME_TEAM, 60)), participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW))
                .thenReturn(java.util.Optional.of(snapshot));
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> List.of(goalEvent(((List<Contributor>) inv.getArgument(5)).get(0).playerId())));

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession original = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                false, matchup, 2, 0, MATCH_ROW, "442", "442");
        original.advanceUntilAndSnapshot(30);
        original.applyUserSubAtMinuteAndSnapshot(101L, 112L, 30);
        original.advanceUntilAndSnapshot(50);

        assertNotNull(context.get().getCheckpointJson(), "the full checkpoint was persisted");
        double exactStamina = original.matchStates.get(112L).currentStamina;
        int timelineSize = original.snapshot().getTimeline().size();
        int shots = original.snapshot().getHomeShots();

        @SuppressWarnings("unchecked")
        Map<String, LiveMatchSession> sessions = (Map<String, LiveMatchSession>)
                readField(LiveMatchSimulationService.class, service, "liveMatchSessions");
        sessions.clear(); // simulate a backend restart

        LiveMatchSession recovered = service.getSessionOrRecover(liveKey);
        assertNotNull(recovered);
        assertEquals(50, recovered.currentMinute);
        assertEquals(shots, recovered.snapshot().getHomeShots(), "visible statistics survive restart");
        assertEquals(timelineSize, recovered.snapshot().getTimeline().size(), "timeline survives restart");
        assertEquals(exactStamina, recovered.matchStates.get(112L).currentStamina, 0.0000001,
                "engine stamina is restored without DTO rounding");
        assertFalse(recovered.matchStates.get(101L).isOnPitch);
        assertTrue(recovered.matchStates.get(112L).isOnPitch);
        assertTrue(recovered.hasManualSubstitutions(), "manual-sub provenance survives restart");

        recovered.advanceUntilAndSnapshot(60);
        ArgumentCaptor<Integer> slots = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<List> candidates = ArgumentCaptor.forClass(List.class);
        verify(matchPlanService, atLeastOnce()).resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(),
                slots.capture(), candidates.capture());
        int secondGoalCall = slots.getAllValues().lastIndexOf(1);
        assertTrue(secondGoalCall >= 0);
        @SuppressWarnings("unchecked")
        List<Contributor> atSixty = candidates.getAllValues().get(secondGoalCall);
        Contributor substitute = atSixty.stream().filter(c -> c.playerId() == 112L).findFirst().orElseThrow();
        assertEquals("DC", substitute.position(), "the substitute keeps the inherited fielded role");
    }

    @Test
    void flagOff_doesNotPrepareOrBind_legacyNarration() {
        engineConfig.getMatchPlan().setEnabled(false);
        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));

        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                false, matchup, 2, 1, MATCH_ROW, "4-4-2", "4-4-2");

        assertFalse(session.isCanonicalPlanBound(), "flag off → no canonical plan");
        verify(matchPlanService, never()).prepareLivePlan(anyString(), anyLong(), anyInt(), anyInt(),
                anyLong(), anyLong(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        // Versioned boundary: with the flag off the client sees only the legacy map; the new
        // canonical-animations field is absent (byte-compatible frontend contract).
        assertNull(session.snapshot().getCanonicalAnimations(), "no canonical animations when flag off");
    }

    @Test
    void canonicalExtraTime_playedLiveTo120_consumesEtSlot_andExposesPreDecidedShootout() {
        // Blocker #3: a tied knockout whose ET + shootout were decided BEFORE kickoff. The
        // plan carries an EXTRA_TIME goal slot at 105' and a 4-2 shootout. The live session
        // must extend to 120', consume the ET slot as a real live goal, and expose the
        // pre-decided shootout so /commit derives the winner without re-rolling the tie.
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (long id = 100; id <= 112; id++) participants.add(pv(HOME_TEAM, (int) (id - 100), contrib(id)));
        for (long id = 200; id <= 212; id++) participants.add(pv(AWAY_TEAM, (int) (id - 200), contrib(id)));
        LivePlanSnapshot snap = new LivePlanSnapshot("CTIM:" + MATCH_ROW, 12345L,
                HOME_TEAM, AWAY_TEAM, com.footballmanagergamesimulator.matchplan.MatchPlan.Status.PLANNED, 120, 4, 2,
                List.of(
                        slot(0, HOME_TEAM, 30),                        // 90' goal
                        new LivePlanSnapshot.SlotView(1, HOME_TEAM, 105, GoalPhase.EXTRA_TIME,
                                "OPEN_PLAY", false, null, null),        // extra-time goal
                        slot(2, AWAY_TEAM, 30)),                        // 90' goal → 1-1 at 90
                participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot("CTIM:" + MATCH_ROW)).thenReturn(java.util.Optional.of(snap));
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> List.of(goalEvent(5000 + (int) (long) (Integer) inv.getArgument(4))));

        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        // 18-param overload: 90' 1-1, ET 1-0 home, shootout 4-2.
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                false, matchup, 1, 1, MATCH_ROW, "4-4-2", "4-4-2",
                1, 0, 4, 2);

        assertTrue(session.isCanonicalPlanBound());
        assertTrue(session.getTotalMinutes() >= 120, "live duration extends to 120' for extra time");
        assertTrue(session.isCanonicalExtraTime(), "plan scheduled extra time");
        assertTrue(session.isCanonicalShootout(), "plan carries a pre-decided shootout");
        assertEquals(4, session.getCanonicalShootoutHome());
        assertEquals(2, session.getCanonicalShootoutAway());

        session.advanceUntilAndSnapshot(session.getTotalMinutes());
        assertTrue(session.isFinished());
        // The ET goal at 105' was CONSUMED LIVE (not appended off-screen at commit).
        assertEquals(2, session.getHomeScore(), "90' goal + ET goal both scored live");
        assertEquals(1, session.getAwayScore());
        assertEquals(1, session.canonicalExtraTimeGoals(HOME_TEAM), "one ET goal projected from the plan");
        List<Integer> goalMinutes = session.snapshot().getTimeline().stream()
                .filter(m -> "goal".equals(m.getEventType())).map(m -> m.getMinute()).sorted().toList();
        assertTrue(goalMinutes.contains(105), "the extra-time goal is displayed live at minute 105");
    }

    // ---------------- fixtures ----------------

    private LivePlanSnapshot.SlotView slot(int index, long teamId, int minute) {
        return slot(index, teamId, minute, "OPEN_PLAY");
    }

    private LivePlanSnapshot.SlotView slot(int index, long teamId, int minute, String goalType) {
        return new LivePlanSnapshot.SlotView(index, teamId, minute, GoalPhase.REGULAR_TIME,
                goalType, false, null, null);
    }

    /** 1 GK + 11 outfield + 1 bench player = 13 players. */
    private List<Human> buildSquad(long baseId) {
        List<Human> squad = new ArrayList<>();
        squad.add(human(baseId, "GK", 70));
        String[] outfield = {"DC", "DC", "DL", "DR", "MC", "MC", "ML", "MR", "AMC", "ST", "ST"};
        for (int i = 0; i < outfield.length; i++) squad.add(human(baseId + 1 + i, outfield[i], 70 - i % 5));
        squad.add(human(baseId + 12, "ST", 55)); // bench
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

    private void injectService(String fieldName, Object value) throws Exception {
        injectField(LiveMatchSimulationService.class, service, fieldName, value);
    }

    private static void injectField(Class<?> type, Object target, String fieldName, Object value) throws Exception {
        var field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object readField(Class<?> type, Object target, String fieldName) throws Exception {
        var field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
