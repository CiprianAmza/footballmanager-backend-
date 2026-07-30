package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.animation.AnimationDirector;
import com.footballmanagergamesimulator.animation.AnimationV3Settings;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.GoalPhase;
import com.footballmanagergamesimulator.matchplan.LiveCommitContext;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.LivePlanSnapshot;
import com.footballmanagergamesimulator.matchplan.MatchAnimationRecipe;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LiveCommitContextRepository;
import com.footballmanagergamesimulator.repository.MatchAnimationRecipeRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticalScoreService.Matchup;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared fixture for the six Faza 2 test gates (see {@code MATCH_2D_ENGINE_PLAN.md}
 * §"Test gates înainte de Faza 2" and {@code AI_HANDOFF.md} rev. 6 question 6).
 *
 * <p>It wires one canonical, fully deterministic live fixture the same way
 * {@code LiveMatchCanonicalPlanBindingTest} / {@code AnimationV3LiveWiringTest} do —
 * a hand-built {@link LiveMatchSimulationService} with reflection-injected
 * collaborators — and adds the two stateful fakes the gates need:
 *
 * <ul>
 *   <li>a {@link LiveCommitContext} that behaves like the real row (so
 *       {@code saveLiveCheckpoint} actually writes {@code checkpointJson} and cold
 *       recovery can read it back);</li>
 *   <li>a {@link MatchAnimationRecipeRepository} fake with real write-once semantics
 *       keyed by {@code (fixtureKey, slotIndex)}, so "no duplicate animation after a
 *       retry/recovery" is a real assertion rather than a stub artefact;</li>
 *   <li>a {@link MatchPlanService} whose {@code resolveDueSlot} is idempotent and
 *       whose {@code loadLivePlanSnapshot} reflects already-resolved slots — the
 *       behaviour a cold recovery depends on.</li>
 * </ul>
 *
 * <p>The fixture is canonical (match-plan flag ON, {@code matchRowId > 0}), so the
 * session runs on a seeded {@code CheckpointRandom} and two runs of the same fixture
 * are byte-identical. That property is what makes the ambient on/off comparison in
 * gate 1 meaningful, and it is pinned by
 * {@code LiveAmbientNonInterferenceCharacterizationTest}.
 *
 * <p>Not a test class (no {@code Test} suffix) — it is test-support only.
 */
class Faza2GateHarness {

    static final long HOME_TEAM = 1L;
    static final long AWAY_TEAM = 2L;
    static final long COMP = 10L;
    static final long MATCH_ROW = 999L;
    static final int SEASON = 1;
    static final int ROUND = 7;
    static final String FIXTURE = "CTIM:" + MATCH_ROW;
    static final String LIVE_KEY = COMP + "_" + SEASON + "_" + ROUND + "_" + HOME_TEAM + "_" + AWAY_TEAM;
    static final int TARGET_HOME = 2;
    static final int TARGET_AWAY = 1;

    /** Real positions with a GK at index 0, so both on-pitch sets have a keeper. */
    static final String[] POS = {"GK", "DC", "DC", "DL", "DR", "MC", "MC", "ML", "MR", "AMC", "ST"};

    final MatchEngineConfig engineConfig = new MatchEngineConfig();
    final LiveMatchSimulationService service = new LiveMatchSimulationService();
    final TacticalScoreService tacticalScoreService = new TacticalScoreService();
    final ObjectMapper mapper = new ObjectMapper();

    final MatchPlanService matchPlanService = mock(MatchPlanService.class);
    final GoalAnimationService goalAnimationService = mock(GoalAnimationService.class);
    final HumanRepository humanRepository = mock(HumanRepository.class);
    final TeamRepository teamRepository = mock(TeamRepository.class);
    final CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
    final PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
    final MatchEventRepository matchEventRepository = mock(MatchEventRepository.class);
    final LiveCommitContextRepository liveCommitContextRepository = mock(LiveCommitContextRepository.class);
    final MatchAnimationRecipeRepository matchAnimationRecipeRepository = mock(MatchAnimationRecipeRepository.class);

    /** The persisted commit-context row (holds checkpointMinute / randomState / checkpointJson). */
    final AtomicReference<LiveCommitContext> commitContext = new AtomicReference<>();
    /** Write-once recipe store, keyed "fixtureKey|slotIndex". */
    final Map<String, MatchAnimationRecipe> recipeRows = Collections.synchronizedMap(new LinkedHashMap<>());
    /** Every slotIndex passed to resolveDueSlot, in call order (duplicates are visible). */
    final List<Integer> resolveCalls = Collections.synchronizedList(new ArrayList<>());
    /** slotIndex -> scorer, so a re-resolution returns the same persisted scorer. */
    final Map<Integer, Long> resolvedScorers = Collections.synchronizedMap(new LinkedHashMap<>());
    /** slotIndex -> the eligible-scorer candidate ids offered to the resolver. */
    final Map<Integer, List<Long>> candidateIdsBySlot = Collections.synchronizedMap(new LinkedHashMap<>());

    private final List<int[]> plannedSlots; // {slotIndex, homeFlag, minute}

    /** Default plan: home 20', away 40', home 60' — 2-1, with a two-goal ordering check available. */
    Faza2GateHarness() throws Exception {
        this(List.of(new int[]{0, 1, 20}, new int[]{1, 0, 40}, new int[]{2, 1, 60}));
    }

    Faza2GateHarness(List<int[]> plannedSlots) throws Exception {
        this.plannedSlots = plannedSlots;
        engineConfig.getMatchPlan().setEnabled(true); // canonical plan flag ON

        inject("humanRepository", humanRepository);
        inject("teamRepository", teamRepository);
        inject("competitionRepository", competitionRepository);
        inject("playerSkillsRepository", playerSkillsRepository);
        inject("matchEventRepository", matchEventRepository);
        inject("goalAnimationService", goalAnimationService);
        inject("engineConfig", engineConfig);
        inject("matchPlanService", matchPlanService);
        inject("liveCommitContextRepository", liveCommitContextRepository);
        inject("matchAnimationRecipeRepository", matchAnimationRecipeRepository);
        inject("objectMapper", mapper);

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

        // Snapshot is rebuilt on every read so it reflects slots resolved so far — exactly
        // what a cold recovery reads back from the database.
        when(matchPlanService.loadLivePlanSnapshot(FIXTURE)).thenAnswer(inv -> Optional.of(snapshot()));
        when(matchPlanService.resolveDueSlot(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> {
                    int slotIndex = inv.getArgument(4);
                    List<Contributor> onPitch = inv.getArgument(5);
                    resolveCalls.add(slotIndex);
                    candidateIdsBySlot.put(slotIndex,
                            onPitch.stream().map(Contributor::playerId).sorted().toList());
                    Long already = resolvedScorers.get(slotIndex);
                    long scorer = already != null ? already : onPitch.stream()
                            .filter(c -> !c.isGoalkeeper())
                            .findFirst().map(Contributor::playerId).orElse(0L);
                    resolvedScorers.put(slotIndex, scorer);
                    return List.of(goalEvent(scorer));
                });

        // A real commit-context row exists (written by the round simulator in production),
        // so saveLiveCheckpoint is not a no-op and checkpointJson is really produced.
        commitContext.set(new LiveCommitContext(LIVE_KEY, MATCH_ROW, "442", "442",
                100.0, 80.0, false, 0, 0L, 0));
        when(liveCommitContextRepository.findByLiveKey(LIVE_KEY))
                .thenAnswer(inv -> Optional.ofNullable(commitContext.get()));
        when(liveCommitContextRepository.save(any())).thenAnswer(inv -> {
            commitContext.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        // Write-once recipe persistence with real (fixtureKey, slotIndex) semantics.
        when(matchAnimationRecipeRepository.findByFixtureKeyAndSlotIndex(anyString(), anyInt()))
                .thenAnswer(inv -> Optional.ofNullable(
                        recipeRows.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(matchAnimationRecipeRepository.save(any())).thenAnswer(inv -> {
            MatchAnimationRecipe row = inv.getArgument(0);
            recipeRows.putIfAbsent(row.getFixtureKey() + "|" + row.getSlotIndex(), row);
            return row;
        });
        when(matchAnimationRecipeRepository.findByFixtureKeyOrderByMinuteAscSlotIndexAsc(anyString()))
                .thenAnswer(inv -> recipeRows.values().stream()
                        .filter(r -> r.getFixtureKey().equals(inv.getArgument(0)))
                        .sorted(java.util.Comparator.comparingInt(MatchAnimationRecipe::getMinute)
                                .thenComparingInt(MatchAnimationRecipe::getSlotIndex))
                        .toList());
    }

    // ---- session lifecycle ------------------------------------------------

    /** Render real Animation V3 frames for this fixture (off by default: it is slow). */
    void enableAnimationV3() throws Exception {
        AnimationV3Settings settings = new AnimationV3Settings() {
            @Override public boolean enabled() { return true; }
        };
        GoalAnimationContext ctx = new GoalAnimationContext() {
            @Override public void attachKits(GoalAnimationData d, long s, long de) { /* no repos here */ }
        };
        inject("animationV3GoalAdapter", new AnimationV3GoalAdapter(settings, new AnimationDirector(), ctx));
    }

    LiveMatchSession start() {
        return start(false);
    }

    LiveMatchSession start(boolean generateGoalAnimations) {
        return service.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, SEASON, ROUND,
                generateGoalAnimations, matchup(), TARGET_HOME, TARGET_AWAY,
                MATCH_ROW, "442", "442");
    }

    /** Simulate a backend restart: drop every in-memory session. */
    @SuppressWarnings("unchecked")
    void dropInMemorySessions() throws Exception {
        var f = LiveMatchSimulationService.class.getDeclaredField("liveMatchSessions");
        f.setAccessible(true);
        ((Map<String, LiveMatchSession>) f.get(service)).clear();
    }

    /** Cold recovery through the production resume path. */
    LiveMatchSession coldRecover() {
        return service.getSessionOrRecover(LIVE_KEY);
    }

    Matchup matchup() {
        return tacticalScoreService.matchup(
                new TeamProfile(70, 55), new TacticVector(0.4, 0.3, 0.2),
                new TeamProfile(50, 45), new TacticVector(-0.2, -0.1, 0.5));
    }

    // ---- checkpoint access ------------------------------------------------

    String checkpointJson() {
        return commitContext.get() == null ? null : commitContext.get().getCheckpointJson();
    }

    LiveSessionCheckpoint checkpoint() throws Exception {
        String json = checkpointJson();
        return json == null ? null : mapper.readValue(json, LiveSessionCheckpoint.class);
    }

    /** The live engine RNG checkpoint word, or null when the session is not canonical. */
    static Long liveRandomState(LiveMatchSession session) throws Exception {
        var f = LiveMatchSession.class.getDeclaredField("random");
        f.setAccessible(true);
        Object random = f.get(session);
        return random instanceof CheckpointRandom cr ? cr.checkpointState() : null;
    }

    // ---- comparable projections of the canonical outcome -------------------

    /** Every canonical fact gate 1 requires to be identical, as a comparable string. */
    static String canonicalFingerprint(LiveMatchData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("score=").append(d.getHomeScore()).append(':').append(d.getAwayScore()).append('\n');
        sb.append("minute=").append(d.getCurrentMinute())
                .append(" finished=").append(d.isFinished())
                .append(" awaitingCommit=").append(d.isAwaitingCommit()).append('\n');
        sb.append("stats=").append(d.getHomePossession()).append('/').append(d.getAwayPossession())
                .append(' ').append(d.getHomeShots()).append('/').append(d.getAwayShots())
                .append(' ').append(d.getHomeShotsOnTarget()).append('/').append(d.getAwayShotsOnTarget())
                .append(' ').append(d.getHomeXg()).append('/').append(d.getAwayXg())
                .append(' ').append(d.getHomeCorners()).append('/').append(d.getAwayCorners())
                .append(' ').append(d.getHomeFouls()).append('/').append(d.getAwayFouls())
                .append(' ').append(d.getHomeYellowCards()).append('/').append(d.getAwayYellowCards())
                .append(' ').append(d.getHomeRedCards()).append('/').append(d.getAwayRedCards())
                .append(' ').append(d.getHomeOffsides()).append('/').append(d.getAwayOffsides())
                .append(' ').append(d.getFirstHalfStoppage()).append('/').append(d.getSecondHalfStoppage())
                .append(' ').append(d.getHomeSubsRemaining()).append('/').append(d.getAwaySubsRemaining())
                .append('\n');
        for (LiveMatchData.LiveMatchMinute m : d.getTimeline()) {
            sb.append("ev ").append(m.getMinute()).append(' ').append(m.getEventType())
                    .append(' ').append(m.getHomeScore()).append(':').append(m.getAwayScore())
                    .append(" p=").append(m.getPlayerId()).append(" t=").append(m.getTeamId())
                    .append(" | ").append(m.getCommentary()).append('\n');
        }
        sb.append(pitchFingerprint("home", d.getHomePitch()));
        sb.append(pitchFingerprint("away", d.getAwayPitch()));
        sb.append(pitchFingerprint("homeBench", d.getHomeBench()));
        sb.append(pitchFingerprint("awayBench", d.getAwayBench()));
        if (d.getCanonicalAnimations() != null) {
            for (GoalAnimationData a : d.getCanonicalAnimations()) {
                sb.append("anim ").append(a.getMinute()).append('/').append(a.getSlotIndex())
                        .append(' ').append(a.getOutcome()).append(' ').append(a.getScorerPlayerId())
                        .append(' ').append(a.getTotalFrames()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String pitchFingerprint(String label, List<LiveMatchData.PlayerStaminaInfo> players) {
        if (players == null) return label + "=null\n";
        StringBuilder sb = new StringBuilder();
        for (LiveMatchData.PlayerStaminaInfo p : players) {
            sb.append(label).append(' ').append(p.getPlayerId()).append(' ').append(p.getPosition())
                    .append(' ').append(p.getStamina()).append(' ').append(p.getMinutesPlayed())
                    .append(' ').append(p.isOnPitch()).append(' ').append(p.getYellowCardMinute())
                    .append(' ').append(p.getRedCardMinute()).append('\n');
        }
        return sb.toString();
    }

    /** Goal minutes + scorers in timeline order — the "future canonical goals" of gate 2. */
    static List<String> goalFacts(LiveMatchData d) {
        return d.getTimeline().stream()
                .filter(m -> "goal".equals(m.getEventType()))
                .map(m -> m.getMinute() + "/" + m.getTeamId() + "/" + m.getPlayerId())
                .toList();
    }

    // ---- fixtures ---------------------------------------------------------

    private LivePlanSnapshot snapshot() {
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (int i = 0; i < POS.length; i++) participants.add(pv(HOME_TEAM, i, true, contrib(100 + i, POS[i])));
        for (int i = 0; i < 2; i++) participants.add(pv(HOME_TEAM, POS.length + i, false, contrib(111 + i, "ST")));
        for (int i = 0; i < POS.length; i++) participants.add(pv(AWAY_TEAM, i, true, contrib(200 + i, POS[i])));
        for (int i = 0; i < 2; i++) participants.add(pv(AWAY_TEAM, POS.length + i, false, contrib(211 + i, "ST")));

        List<LivePlanSnapshot.SlotView> slots = new ArrayList<>();
        for (int[] s : plannedSlots) {
            Long scorer = resolvedScorers.get(s[0]);
            slots.add(new LivePlanSnapshot.SlotView(s[0], s[1] == 1 ? HOME_TEAM : AWAY_TEAM, s[2],
                    GoalPhase.REGULAR_TIME, "OPEN_PLAY", scorer != null, scorer, null));
        }
        return new LivePlanSnapshot(FIXTURE, 12345L, HOME_TEAM, AWAY_TEAM,
                MatchPlan.Status.IN_PROGRESS, 90, -1, -1, slots, participants, List.of());
    }

    private static LivePlanSnapshot.ParticipantView pv(long teamId, int index, boolean starter, Contributor c) {
        return new LivePlanSnapshot.ParticipantView(teamId, index, starter, c);
    }

    private static Contributor contrib(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 14.0, 12, 12, 12, 100.0, false, false);
    }

    private static Lineup kickoffXi(long base) {
        List<Contributor> xi = new ArrayList<>();
        for (int i = 0; i < POS.length; i++) xi.add(contrib(base + i, POS[i]));
        return new Lineup(xi, List.of());
    }

    private static MatchEvent goalEvent(long scorerId) {
        MatchEvent e = new MatchEvent();
        e.setEventType("goal");
        e.setPlayerId(scorerId);
        e.setPlayerName("P" + scorerId);
        return e;
    }

    /** 11 starters + 2 bench players per side. */
    private static List<Human> squad(long baseId) {
        List<Human> squad = new ArrayList<>();
        for (int i = 0; i < POS.length; i++) squad.add(human(baseId + i, POS[i]));
        squad.add(human(baseId + 11, "ST"));
        squad.add(human(baseId + 12, "MC"));
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

    private void inject(String field, Object value) throws Exception {
        var f = LiveMatchSimulationService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }
}
