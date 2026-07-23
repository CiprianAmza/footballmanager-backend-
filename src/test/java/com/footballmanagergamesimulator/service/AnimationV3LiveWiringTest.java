package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.animation.AnimationDirector;
import com.footballmanagergamesimulator.animation.AnimationV3Settings;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.GoalPhase;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.LivePlanSnapshot;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.TacticalScoreService.Matchup;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end proof of the presentation-only Animation Engine V3 LIVE wiring. Driving a real
 * {@link LiveMatchSession} through a canonical goal:
 * <ul>
 *   <li>flag ON → the canonical animation is rendered by V3 (generatorVersion 2, real frames)
 *       while every canonical fact (minute, slotIndex, fixtureKey, scorer) is preserved;</li>
 *   <li>flag OFF → the exact legacy engine runs (generatorVersion 1), so behaviour is unchanged.</li>
 * </ul>
 * The V3 engine is presentation-only: it never changes the persisted scoreline or scorer.
 */
class AnimationV3LiveWiringTest {

    private LiveMatchSimulationService service;
    private TacticalScoreService tacticalScoreService;
    private MatchEngineConfig engineConfig;
    private MatchPlanService matchPlanService;
    private GoalAnimationService goalAnimationService;
    private MatchAnimationRecipeRepository matchAnimationRecipeRepository;

    private static final long HOME_TEAM = 1L;
    private static final long AWAY_TEAM = 2L;
    private static final long COMP = 10L;
    private static final long MATCH_ROW = 999L;
    private static final String FIXTURE = "CTIM:" + MATCH_ROW;
    /** Real positions with a goalkeeper at index 0, so the defending on-pitch set has a GK. */
    private static final String[] POS = {"GK", "DC", "DC", "DL", "DR", "MC", "MC", "ML", "MR", "AMC", "ST"};

    @BeforeEach
    void setUp() throws Exception {
        engineConfig = new MatchEngineConfig();
        engineConfig.getTacticalModel().setEnabled(true);
        engineConfig.getMatchPlan().setEnabled(true); // canonical plan flag ON

        service = new LiveMatchSimulationService();
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
        MatchEventRepository matchEventRepository = mock(MatchEventRepository.class);
        goalAnimationService = mock(GoalAnimationService.class);
        matchPlanService = mock(MatchPlanService.class);
        matchAnimationRecipeRepository = mock(MatchAnimationRecipeRepository.class);

        inject("humanRepository", humanRepository);
        inject("teamRepository", teamRepository);
        inject("competitionRepository", competitionRepository);
        inject("playerSkillsRepository", playerSkillsRepository);
        inject("matchEventRepository", matchEventRepository);
        inject("goalAnimationService", goalAnimationService);
        inject("engineConfig", engineConfig);
        inject("matchPlanService", matchPlanService);
        inject("matchAnimationRecipeRepository", matchAnimationRecipeRepository);
        inject("objectMapper", new ObjectMapper());

        tacticalScoreService = new TacticalScoreService();
        var f = TacticalScoreService.class.getDeclaredField("engineConfig");
        f.setAccessible(true);
        f.set(tacticalScoreService, engineConfig);

        when(humanRepository.findAllByTeamIdAndTypeId(HOME_TEAM, 1L)).thenReturn(squad(100L));
        when(humanRepository.findAllByTeamIdAndTypeId(AWAY_TEAM, 1L)).thenReturn(squad(200L));
        when(teamRepository.findNameById(HOME_TEAM)).thenReturn("Home FC");
        when(teamRepository.findNameById(AWAY_TEAM)).thenReturn("Away FC");
        when(competitionRepository.findNameById(COMP)).thenReturn("Test League");
        when(playerSkillsRepository.findAllByPlayerIdIn(any())).thenReturn(Collections.emptyList());
        when(matchPlanService.isEnabled()).thenAnswer(inv -> engineConfig.getMatchPlan().isEnabled());
        when(matchPlanService.buildKickoffLineups(anyString(), anyLong(), anyInt(), anyInt(),
                anyLong(), anyLong(), any(), any()))
                .thenReturn(new MatchPlanService.KickoffLineups(kickoffXi(100), kickoffXi(200)));
        // Idempotent recipe persistence: no existing row → the animation is saved.
        when(matchAnimationRecipeRepository.findByFixtureKeyAndSlotIndex(anyString(), anyInt()))
                .thenReturn(Optional.empty());

        // One home goal at minute 30 (slot 0); resolve it to an on-pitch outfielder.
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (int i = 0; i < POS.length; i++) participants.add(pv(HOME_TEAM, i, contrib(100 + i, POS[i])));
        for (int i = 0; i < POS.length; i++) participants.add(pv(AWAY_TEAM, i, contrib(200 + i, POS[i])));
        LivePlanSnapshot snap = new LivePlanSnapshot(FIXTURE, 12345L, HOME_TEAM, AWAY_TEAM,
                MatchPlan.Status.PLANNED, 90, -1, -1,
                List.of(slot(0, HOME_TEAM, 30)), participants, List.of());
        when(matchPlanService.loadLivePlanSnapshot(FIXTURE)).thenReturn(Optional.of(snap));
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> {
                    List<Contributor> onPitch = inv.getArgument(5);
                    long scorer = onPitch.stream().filter(c -> !c.isGoalkeeper())
                            .findFirst().map(Contributor::playerId).orElse(0L);
                    return List.of(goalEvent(scorer));
                });
        // Legacy engine (used when the V3 flag is off) returns a version-1 animation.
        when(goalAnimationService.generate(anyList(), anyList(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyInt(), anyString()))
                .thenAnswer(inv -> new GoalAnimationData());
    }

    @Test void flagOn_canonicalGoalIsRenderedByV3_preservingCanonicalFacts() throws Exception {
        useV3Adapter(true);
        LiveMatchSession session = playToFullTime();

        assertEquals(1, session.getHomeScore(), "the canonical goal still lands");
        List<GoalAnimationData> canon = session.snapshot().getCanonicalAnimations();
        assertEquals(1, canon.size());
        GoalAnimationData a = canon.get(0);
        assertEquals(2, a.getGeneratorVersion(), "rendered by Animation Engine V3 (version 2)");
        assertFalse(a.getFrames().isEmpty(), "V3 produced real frames");
        assertEquals(a.getFrames().size() - 1, a.getTotalFrames());
        // Canonical facts are preserved exactly — the animation is presentation-only.
        assertEquals(30, a.getMinute());
        assertEquals(0, a.getSlotIndex());
        assertEquals(FIXTURE, a.getFixtureKey());
        assertEquals(HOME_TEAM, a.getScoringTeamId());
        assertEquals(session.snapshot().getTimeline().stream()
                .filter(m -> "goal".equals(m.getEventType())).findFirst().orElseThrow().getPlayerId(),
                a.getScorerPlayerId(), "displayed scorer == animated scorer");
        // The versioned recipe was persisted (durable, collision-safe).
        verify(matchAnimationRecipeRepository, times(1)).save(any());
    }

    @Test void flagOff_canonicalGoalUsesLegacyEngine_unchanged() throws Exception {
        useV3Adapter(false);
        LiveMatchSession session = playToFullTime();

        assertEquals(1, session.getHomeScore());
        List<GoalAnimationData> canon = session.snapshot().getCanonicalAnimations();
        assertEquals(1, canon.size());
        assertEquals(GoalAnimationData.GENERATOR_VERSION, canon.get(0).getGeneratorVersion(),
                "flag off → legacy engine (version 1), behaviour unchanged");
        // The legacy engine was invoked; the V3 director was not on the path.
        verify(goalAnimationService, atLeastOnce()).generate(anyList(), anyList(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyInt(), anyString());
    }

    // ---- harness -------------------------------------------------------

    private void useV3Adapter(boolean flagOn) throws Exception {
        AnimationV3Settings settings = new AnimationV3Settings() {
            @Override public boolean enabled() { return flagOn; }
        };
        GoalAnimationContext ctx = new GoalAnimationContext() {
            @Override public void attachKits(GoalAnimationData d, long s, long de) { /* no repos here */ }
        };
        inject("animationV3GoalAdapter", new AnimationV3GoalAdapter(settings, new AnimationDirector(), ctx));
    }

    private LiveMatchSession playToFullTime() {
        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
        LiveMatchSession session = service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 7,
                true, matchup, 1, 0, MATCH_ROW, "4-4-2", "4-4-2"); // animations ON
        session.advanceUntilAndSnapshot(session.getTotalMinutes());
        return session;
    }

    private void inject(String field, Object value) throws Exception {
        var f = LiveMatchSimulationService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private Lineup kickoffXi(long base) {
        List<Contributor> xi = new ArrayList<>();
        for (int i = 0; i < POS.length; i++) xi.add(contrib(base + i, POS[i]));
        return new Lineup(xi, List.of());
    }

    private static Contributor contrib(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 14.0, 12, 12, 12, 100.0, false, false);
    }

    private LivePlanSnapshot.ParticipantView pv(long teamId, int index, Contributor c) {
        return new LivePlanSnapshot.ParticipantView(teamId, index, true, c);
    }

    private LivePlanSnapshot.SlotView slot(int index, long teamId, int minute) {
        return new LivePlanSnapshot.SlotView(index, teamId, minute, GoalPhase.REGULAR_TIME,
                "OPEN_PLAY", false, null, null);
    }

    private MatchEvent goalEvent(long scorerId) {
        MatchEvent e = new MatchEvent();
        e.setEventType("goal");
        e.setPlayerId(scorerId);
        e.setPlayerName("P" + scorerId);
        return e;
    }

    private List<Human> squad(long baseId) {
        List<Human> squad = new ArrayList<>();
        squad.add(human(baseId, "GK"));
        for (int i = 1; i < POS.length; i++) squad.add(human(baseId + i, POS[i]));
        squad.add(human(baseId + POS.length, "ST")); // bench
        return squad;
    }

    private static Human human(long id, String position) {
        Human h = new Human();
        h.setId(id);
        h.setName(position + "_" + id);
        h.setShirtNumber((int) (id % 99) + 1);
        h.setPosition(position);
        h.setRating(70);
        h.setRetired(false);
        h.setFitness(100.0);
        return h;
    }
}
