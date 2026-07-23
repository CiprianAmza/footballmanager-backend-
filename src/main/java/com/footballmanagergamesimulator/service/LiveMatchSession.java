package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute;
import com.footballmanagergamesimulator.frontend.LiveMatchData.PlayerStaminaInfo;
import com.footballmanagergamesimulator.frontend.LiveMatchData.StaminaSnapshot;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.GoalPhase;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.LivePlanSnapshot;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.InvalidSubstitutionException;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.PlayerMatchState;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.SubReason;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService.SubSwap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tickable live-match engine. Holds all per-match state (squads, scores,
 * timeline, stamina, subs) and exposes the public surface used by
 * MatchController endpoints (advance / snapshot / substitute / commit) plus
 * the deferred-context plumbing used by CompetitionController.
 *
 * <p>This used to be an inner class of {@link LiveMatchSimulationService}.
 * It was hoisted to its own file once {@code tickOneMinute} was split into
 * branch helpers — the file is large enough to deserve top-level treatment
 * and the {@code svc.}-prefixed references make the engine/service split
 * explicit. The service still owns the autowired repositories, stateless
 * helpers (pickWeightedAttacker, pickFouler, applyStaminaTick, …), and the
 * commentary / description constants — session reaches into them via the
 * package-private {@code svc} reference.
 */
public class LiveMatchSession {

    private final LiveMatchSimulationService svc;

    // --- Identity / inputs (final) ---
    final long teamId1, teamId2;
    final long competitionId;
    final int season;
    final int round;
    final boolean generateGoalAnimations;
    final String homeTeamName, awayTeamName, competitionName;

    // --- Squads ---
    final List<Human> team1Outfield, team2Outfield;
    final List<Human> team1All, team2All;
    final Set<Long> team1Ids, team2Ids;

    // --- Stamina model ---
    final Map<Long, PlayerMatchState> matchStates;
    final List<StaminaSnapshot> staminaSnapshots = new ArrayList<>();

    // --- Output containers ---
    // LEGACY: cosmetic (save/miss) animations, and — with the flag off — goal animations,
    // keyed by minute (last at a minute wins). Byte-compatible with the existing frontend.
    final Map<Integer, GoalAnimationData> goalAnimations;
    // CANONICAL (flag on): the plan's goal animations in order, each carrying its
    // (minute, slotIndex, fixtureKey) so the frontend queues by (minute, slotIndex).
    final List<GoalAnimationData> canonicalAnimations = new ArrayList<>();
    final List<LiveMatchMinute> timeline = new ArrayList<>();
    final List<MatchEvent> dbEvents = new ArrayList<>();

    // --- Score / stats ---
    int homeScore = 0, awayScore = 0;
    int homeShots = 0, awayShots = 0;
    int homeShotsOnTarget = 0, awayShotsOnTarget = 0;
    int homeCorners = 0, awayCorners = 0;
    int homeFouls = 0, awayFouls = 0;
    int homeYellowCards = 0, awayYellowCards = 0;
    int homeRedCards = 0, awayRedCards = 0;
    int homeOffsides = 0, awayOffsides = 0;
    int homePossessionMinutes = 0;

    // --- Sub tracking ---
    int homeSubsUsed = 0, awaySubsUsed = 0;
    int homeLastSubMin = -10, awayLastSubMin = -10;

    // --- Pre-computed schedules ---
    final double team1PossChance;
    final double team1AttackChance, team2AttackChance;
    final Set<Integer> team1BigChanceMinutes, team2BigChanceMinutes;
    int firstHalfStoppage, secondHalfStoppage, halfTimeMinute;
    // Non-final: extended to 120'+stoppage when a bound canonical plan includes extra time,
    // so a knockout that goes to ET is PLAYED live (minutes 91-120) rather than decided offscreen.
    int totalMinutes;

    // --- Pinned scoreline (engine unification) ---
    // When >= 0 the live narration must end on exactly these goal totals (the
    // instant two-axis engine's scoreline for this match). The attack branch
    // forces a goal only at the pre-scheduled minutes below and caps the goal
    // outcome to 0 everywhere else, so the live final score == the instant one.
    // -1 = legacy stochastic mode (no target; goals emerge from the rolls).
    final int targetHomeGoals, targetAwayGoals;
    // Non-final: when a canonical MatchPlan is bound after construction, these are
    // replaced by the plan's fixed regular-time goal minutes (see bindCanonicalPlan).
    Set<Integer> homeGoalMinutes, awayGoalMinutes;
    private final boolean pinned;
    final boolean deferPersistenceUntilCommit;

    // --- Canonical MatchPlan binding (null = legacy stochastic narration) ---
    // When bound (feature flag on), the watched match consumes the plan's persisted
    // goal minutes/sides instead of the session's own pickRandomMinutes schedule, and
    // resolves each goal's scorer/assist through the shared ContributionResolver.
    private LivePlanSnapshot canonicalPlan;
    private String canonicalFixtureKey;
    // The authoritative kickoff XI adopted from the LineupAdapter (saved first11 / AI XI,
    // with designated takers). Null = not adopted (legacy or fallback to the session's own
    // chosen eleven).
    private Lineup adoptedHomeXi, adoptedAwayXi;
    // Exact canonical goal minute -> the slot indices due then, per side. Minutes are
    // NEVER nudged: colliding goals keep the same minute and are played in slotIndex
    // order (per the canonical/recovery contract). Multiple slots per minute supported.
    private final Map<Integer, List<Integer>> homeMinuteToSlots = new HashMap<>();
    private final Map<Integer, List<Integer>> awayMinuteToSlots = new HashMap<>();
    // slotIndex -> the full slot view (its phase and goal type), so a canonical goal is
    // narrated + animated as its canonical type (penalty / free kick / open play).
    private final Map<Integer, LivePlanSnapshot.SlotView> slotByIndex = new HashMap<>();
    // Canonical slot indices already emitted (resolved + persisted + scored) this session,
    // so a failed advance that is retried never double-counts an already-emitted goal.
    private final Set<Integer> emittedCanonicalSlots = new java.util.HashSet<>();
    // playerId -> the tactical position he currently occupies. Seeded from the kickoff
    // participant snapshot; a substitute inherits the position of the player he replaces,
    // so the canonical resolver weights him in the role he actually enters.
    private final Map<Long, String> fieldedPosition = new HashMap<>();

    /** Scorer + assist resolved from a canonical goal slot, for live display. */
    private record ResolvedGoal(long scorerId, String scorerName, Long assistId) {}

    // Cold recovery: substitutions already persisted (pre-restart), replayed by minute as the
    // recovered session re-advances — applied to the on-pitch state WITHOUT re-persisting.
    private record RecordedSub(long teamId, int minute, long offId, long onId) {}
    private final Map<Integer, List<RecordedSub>> subsToReplay = new HashMap<>();

    // --- Engine state ---
    // Non-final so determinism IT can swap in a seeded Random via
    // setRandomForTesting() after construction.
    Random random;
    int currentMinute = 0;
    boolean finished = false;
    boolean deferredArtifactsPersisted = false;
    /** Flipped to true by {@link #markCommitted()} once the post-match
     *  work has been persisted. Used by GameAdvanceService to know
     *  whether to skip suspensions/news/PC for this match (they happen
     *  on /commit instead). */
    boolean committed = false;
    final List<ManualSubstitution> manualSubstitutions = new ArrayList<>();

    record ManualSubstitution(long teamId, long playerOutId, long playerInId, int minute) {}

    public boolean isCommitted() { return committed; }
    public boolean isFinished() { return finished; }
    public boolean isAwaitingCommit() { return finished && !committed; }
    public int getHomeScore() { return homeScore; }
    public int getAwayScore() { return awayScore; }
    public long getTeamId1() { return teamId1; }
    public long getTeamId2() { return teamId2; }
    public long getCompetitionId() { return competitionId; }
    public int getSeason() { return season; }
    public int getRound() { return round; }
    public int getTotalMinutes() { return totalMinutes; }

    // ---------------- Canonical MatchPlan binding ----------------

    /** True when a persisted canonical plan drives this session's goals (flag on). */
    public boolean isCanonicalPlanBound() { return canonicalPlan != null; }
    public String getCanonicalFixtureKey() { return canonicalFixtureKey; }

    /** The knockout ET/shootout split, decided BEFORE kickoff (blocker #3), read from
     *  the bound canonical plan so a cold restart derives the same result (no re-roll).
     *  -1 when no canonical plan or no shootout was played. */
    public int getCanonicalShootoutHome() { return canonicalPlan == null ? -1 : canonicalPlan.homeShootout(); }
    public int getCanonicalShootoutAway() { return canonicalPlan == null ? -1 : canonicalPlan.awayShootout(); }
    /** True when the bound plan schedules extra time (played live to 120'). */
    public boolean isCanonicalExtraTime() { return canonicalPlan != null && canonicalPlan.hadExtraTime(); }
    /** True when the bound plan carries a shootout result (decided on penalties). */
    public boolean isCanonicalShootout() { return canonicalPlan != null && canonicalPlan.hadShootout(); }
    /** Extra-time goals for a team, projected from the bound plan's EXTRA_TIME slots. */
    public int canonicalExtraTimeGoals(long teamId) {
        return canonicalPlan == null ? 0 : canonicalPlan.extraTimeSlots(teamId).size();
    }

    /**
     * Build the kickoff squad snapshot for the canonical plan FROM this session's own
     * chosen eleven (on-pitch = starters, the rest = bench), so the persisted
     * participants match exactly who the live engine has on the pitch. Per-match
     * attributes come from {@link PlayerMatchState}; rating from the {@link Human} row.
     * Set-piece takers are unknown at kickoff (deferred tactics arrive later), so
     * false here; they do not affect open-play scorer weighting.
     */
    Lineup canonicalKickoffLineup(boolean home) {
        List<Human> squad = home ? team1All : team2All;
        Lineup adopted = home ? adoptedHomeXi : adoptedAwayXi;
        if (adopted != null) {
            // Authoritative XI (with designated takers) from the adapter; bench = the rest
            // of the squad, so ANY player the user can bring on is in the snapshot.
            List<Contributor> starters = adopted.getStartingXI();
            Set<Long> starterIds = starters.stream().map(Contributor::playerId).collect(Collectors.toSet());
            List<Contributor> bench = new ArrayList<>();
            for (Human h : squad) {
                if (starterIds.contains(h.getId())) continue;
                PlayerMatchState st = matchStates.get(h.getId());
                if (st == null) continue;
                bench.add(new Contributor(h.getId(), st.name, st.position, h.getRating(),
                        st.finishing, st.passing, st.vision, st.startFitness, false, false));
            }
            return new Lineup(starters, bench, List.of());
        }
        // Fallback: the session's own on-pitch selection (no takers available).
        List<Contributor> starters = new ArrayList<>();
        List<Contributor> bench = new ArrayList<>();
        for (Human h : squad) {
            PlayerMatchState st = matchStates.get(h.getId());
            if (st == null) continue;
            Contributor c = new Contributor(h.getId(), st.name, st.position, h.getRating(),
                    st.finishing, st.passing, st.vision, st.startFitness, false, false);
            (st.isOnPitch ? starters : bench).add(c);
        }
        return new Lineup(starters, bench, List.of());
    }

    /**
     * Adopt the authoritative kickoff XI (from the {@link com.footballmanagergamesimulator.matchplan.LineupAdapter})
     * as this session's on-pitch set, so a watched match fields exactly the saved eleven the
     * instant path would. Falls back to the session's own chosen eleven if the adapter XI does
     * not cleanly map onto this squad (defensive; never leaves the pitch short).
     */
    synchronized void adoptCanonicalXi(Lineup homeXI, Lineup awayXI) {
        if (!canAdopt(homeXI, team1Ids) || !canAdopt(awayXI, team2Ids)) return;
        this.adoptedHomeXi = homeXI;
        this.adoptedAwayXi = awayXI;
        applyOnPitch(homeXI, team1Ids);
        applyOnPitch(awayXI, team2Ids);
    }

    /** Populate the recorded-substitution replay set for a recovered session (from the
     *  persisted timeline). These are applied — not re-persisted — as the session re-advances. */
    synchronized void loadRecordedSubs(List<LivePlanSnapshot.SubView> subs) {
        subsToReplay.clear();
        if (subs == null) return;
        for (LivePlanSnapshot.SubView s : subs) {
            subsToReplay.computeIfAbsent(s.minute(), k -> new ArrayList<>())
                    .add(new RecordedSub(s.teamId(), s.minute(), s.offPlayerId(), s.onPlayerId()));
        }
    }

    /** Apply a recorded substitution during recovery replay: toggles the on-pitch state and
     *  fielded position and adds a timeline event, but does NOT re-persist (already persisted). */
    private void applyReplaySub(RecordedSub s) {
        PlayerMatchState off = matchStates.get(s.offId());
        PlayerMatchState on = matchStates.get(s.onId());
        if (off == null || on == null || !off.isOnPitch || on.isOnPitch) return; // already applied / invalid
        off.isOnPitch = false;
        on.isOnPitch = true;
        boolean isHome = s.teamId() == teamId1;
        if (isHome) { homeSubsUsed++; homeLastSubMin = s.minute(); }
        else        { awaySubsUsed++; awayLastSubMin = s.minute(); }
        String enteringPos = fieldedPosition.get(s.offId());
        if (enteringPos != null) fieldedPosition.put(s.onId(), enteringPos);
        Human offH = svc.findById(isHome ? team1All : team2All, s.offId());
        Human onH = svc.findById(isHome ? team1All : team2All, s.onId());
        if (offH != null && onH != null) {
            long teamId = isHome ? teamId1 : teamId2;
            String teamName = isHome ? homeTeamName : awayTeamName;
            timeline.add(svc.createMinuteEvent(s.minute(), homeScore, awayScore, "substitution",
                    "Substitution: " + onH.getName() + " on for " + offH.getName(),
                    offH.getId(), offH.getName(), teamId, teamName));
        }
    }

    private boolean canAdopt(Lineup xi, Set<Long> teamIds) {
        return xi != null && xi.getStartingXI().size() == 11
                && xi.getStartingXI().stream().allMatch(c -> teamIds.contains(c.playerId()));
    }

    private void applyOnPitch(Lineup xi, Set<Long> teamIds) {
        Set<Long> starterIds = xi.getStartingXI().stream().map(Contributor::playerId).collect(Collectors.toSet());
        for (Long id : teamIds) {
            PlayerMatchState st = matchStates.get(id);
            if (st != null) st.isOnPitch = starterIds.contains(id);
        }
    }

    /**
     * Bind a persisted canonical plan (feature flag on). The watched match now scores
     * exactly the plan's regular-time goal slots, at their EXACT persisted minutes and
     * sides (never nudged; colliding goals share the minute and play in slotIndex
     * order), each resolved through the shared {@link com.footballmanagergamesimulator.matchplan.ContributionResolver}.
     * Goals are emitted directly from the slots ({@link #emitCanonicalGoalsAt}); the
     * session's own {@code pickRandomMinutes} forcing is disabled (the sets are cleared)
     * so the narration produces only cosmetic events around the fixed goals.
     */
    synchronized void bindCanonicalPlan(LivePlanSnapshot snap, String fixtureKey) {
        this.canonicalPlan = snap;
        this.canonicalFixtureKey = fixtureKey;
        homeMinuteToSlots.clear();
        awayMinuteToSlots.clear();
        slotByIndex.clear();
        // Index ALL slots (regular time AND extra time) by minute per side, so a knockout that
        // goes to extra time PLAYS its 91-120 goals live through the same resolver.
        for (LivePlanSnapshot.SlotView s : snap.slots()) {
            slotByIndex.put(s.slotIndex(), s);
            Map<Integer, List<Integer>> map = s.teamId() == teamId1 ? homeMinuteToSlots
                    : s.teamId() == teamId2 ? awayMinuteToSlots : null;
            if (map != null) map.computeIfAbsent(s.minute(), k -> new ArrayList<>()).add(s.slotIndex());
        }
        // Extra time: extend the live duration so minutes 91-120 are actually played.
        if (snap.durationMinutes() > 90) {
            this.totalMinutes = snap.durationMinutes() + secondHalfStoppage;
        }
        // Seed each player's fielded position from the snapshot (starters in their role,
        // bench in their natural position until they enter).
        fieldedPosition.clear();
        for (LivePlanSnapshot.ParticipantView p : snap.participants()) {
            fieldedPosition.put(p.contributor().playerId(), p.contributor().position());
        }
        // Goals are emitted from the slots, not forced by the pinned narration.
        this.homeGoalMinutes = java.util.Collections.emptySet();
        this.awayGoalMinutes = java.util.Collections.emptySet();
    }

    /** Group a side's regular-time slots by their exact minute (slotIndex order within
     *  a minute is preserved — {@code regularTimeSlots} is already in slot order). */
    private void indexSlotsByMinute(List<LivePlanSnapshot.SlotView> slots, Map<Integer, List<Integer>> byMinute) {
        for (LivePlanSnapshot.SlotView s : slots) {
            byMinute.computeIfAbsent(s.minute(), k -> new ArrayList<>()).add(s.slotIndex());
        }
    }

    /**
     * Emit every canonical goal due at {@code min} — both sides — in global slotIndex
     * order, so simultaneous goals are deterministic and reproducible. Each goal
     * increments the score, resolves its scorer/assist against the players actually on
     * the pitch now (persisted canonically by {@code resolveDueSlot}), and adds the
     * timeline + animation. Called once per minute from {@link #tickOneMinute}.
     */
    private void emitCanonicalGoalsAt(int min) {
        List<int[]> due = new ArrayList<>(); // [slotIndex, home?1:0]
        List<Integer> h = homeMinuteToSlots.get(min);
        List<Integer> a = awayMinuteToSlots.get(min);
        if (h != null) for (int idx : h) due.add(new int[]{idx, 1});
        if (a != null) for (int idx : a) due.add(new int[]{idx, 0});
        if (due.isEmpty()) return;
        due.sort((x, y) -> Integer.compare(x[0], y[0]));
        for (int[] d : due) emitOneCanonicalGoal(min, d[0], d[1] == 1);
    }

    private void emitOneCanonicalGoal(int min, int slotIndex, boolean home) {
        if (emittedCanonicalSlots.contains(slotIndex)) return; // already emitted (idempotent retry)

        // Resolve + PERSIST the goal FIRST. A persistence/concurrency failure propagates so
        // the advance fails and is retried on the same slot — it must NEVER become a
        // displayed goal (no swallowed exception, no player-id-0 phantom goal).
        ResolvedGoal rg = resolveCanonicalSlot(slotIndex, home);
        if (rg == null) {
            throw new IllegalStateException(
                    "Canonical slot " + slotIndex + " did not resolve to a persisted scorer");
        }
        emittedCanonicalSlots.add(slotIndex); // only after the goal is durably persisted

        long teamId = home ? teamId1 : teamId2;
        String teamName = home ? homeTeamName : awayTeamName;
        if (home) { homeScore++; homeShots++; homeShotsOnTarget++; }
        else { awayScore++; awayShots++; awayShotsOnTarget++; }

        long scorerId = rg.scorerId();
        String scorerName = rg.scorerName();
        String goalDesc = LiveMatchSimulationService.GOAL_DESCRIPTIONS[
                random.nextInt(LiveMatchSimulationService.GOAL_DESCRIPTIONS.length)];
        String who = scorerName == null || scorerName.isEmpty() ? teamName : scorerName;
        timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "goal",
                "GOAL! " + who + "! " + goalDesc,
                scorerId, scorerName, teamId, teamName));
        // The canonical goal/assist MatchEvents are persisted by resolveDueSlot, so they
        // are NOT added to dbEvents here (no duplicate rows; displayed == persisted).

        if (goalAnimations != null) {
            Human animScorer = svc.findById(home ? team1All : team2All, scorerId);
            Human animAssister = rg.assistId() != null
                    ? svc.findById(home ? team1All : team2All, rg.assistId()) : null;
            if (animScorer != null) {
                LivePlanSnapshot.SlotView sv = slotByIndex.get(slotIndex);
                String playType = sv != null ? sv.goalType() : "OPEN_PLAY";
                // Presentation-only Animation Engine V3 first (feature flag, default off). It only
                // renders the already-persisted canonical facts and can never change them; empty ⇒
                // flag off or unrenderable ⇒ the legacy engine below runs with unchanged behaviour.
                GoalAnimationData anim =
                        buildCanonicalGoalAnimationV3(min, slotIndex, home, sv, scorerId, rg.assistId())
                                .orElse(null);
                if (anim == null) {
                    // Seed the animation from fixtureKey + slotIndex (not scorer + minute), so
                    // a same-minute goal gets a distinct animation and a refresh/replay
                    // regenerates the identical one. Cleared right after (thread-local).
                    svc.goalAnimationService.setAnimationSeed(animationSeed(slotIndex));
                    // Animate as the canonical goal type (penalty / free kick / open play).
                    try {
                        anim = svc.buildAttackAnimation(
                                svc.filterOnPitch(home ? team1All : team2All, matchStates),
                                svc.filterOnPitch(home ? team2All : team1All, matchStates),
                                animScorer, animAssister,
                                teamId, home ? teamId2 : teamId1, teamId1, min, "GOAL", playType);
                    } finally {
                        svc.goalAnimationService.clearAnimationSeed();
                    }
                }
                // Canonical goal: appended to the ordered (minute, slotIndex) list so
                // simultaneous goals never overwrite each other.
                addCanonicalAnimation(min, slotIndex, anim);
            }
        }
    }

    /**
     * Try the presentation-only Animation Engine V3 for this canonical goal (feature flag,
     * default off). Returns empty when the flag is off, the bean is absent, or the moment
     * cannot be rendered — in which case the caller falls back to the legacy engine. The V3
     * engine is strictly non-authoritative: it renders the already-decided canonical facts
     * (scorer, assister, minute, slot, period, on-pitch lot) and can never alter them. It
     * derives its own restart-stable seed from {@code (planSeed, fixtureKey, slotIndex)}, so a
     * refresh/replay regenerates the identical animation and two same-minute goals stay
     * distinct by {@code slotIndex} without any new RNG.
     */
    private Optional<GoalAnimationData> buildCanonicalGoalAnimationV3(
            int min, int slotIndex, boolean home, LivePlanSnapshot.SlotView sv,
            long scorerId, Long assistId) {
        if (svc.animationV3GoalAdapter == null || !svc.animationV3GoalAdapter.enabled()) return Optional.empty();
        List<Contributor> attackers = canonicalOnPitch(home);
        List<Contributor> defenders = canonicalOnPitch(!home);
        if (attackers.isEmpty() || defenders.isEmpty()) return Optional.empty();
        long scoringTeamId = home ? teamId1 : teamId2;
        long defendingTeamId = home ? teamId2 : teamId1;
        boolean extraTime = sv != null && sv.phase() == GoalPhase.EXTRA_TIME;
        String goalType = sv != null ? sv.goalType() : "OPEN_PLAY";
        long planSeed = canonicalPlan != null ? canonicalPlan.seed() : 0L;
        Map<Long, Integer> shirtNumbers = new HashMap<>();
        for (Human h : team1All) shirtNumbers.put(h.getId(), h.getShirtNumber());
        for (Human h : team2All) shirtNumbers.put(h.getId(), h.getShirtNumber());
        return svc.animationV3GoalAdapter.tryBuildCanonicalGoal(
                canonicalFixtureKey, slotIndex, planSeed, min, extraTime,
                scoringTeamId, defendingTeamId, teamId1, goalType, scorerId, assistId,
                attackers, defenders, shirtNumbers);
    }

    /** Deterministic per-goal animation seed: the plan seed (derived from the fixture
     *  key) mixed with the slot index — restart-stable and distinct per goal, so a
     *  refresh/replay regenerates the exact same animation (finding: durability). */
    private long animationSeed(int slotIndex) {
        long planSeed = canonicalPlan != null ? canonicalPlan.seed() : 0L;
        return planSeed * 2_000_003L + slotIndex;
    }

    /** Resolve one canonical slot against the current on-pitch set and return its
     *  scorer/assist for display, or null if resolution is unavailable (degraded:
     *  the pinned goal still lands, shown against the team). */
    private ResolvedGoal resolveCanonicalSlot(int slotIndex, boolean home) {
        if (svc.matchPlanService == null || canonicalFixtureKey == null) return null;
        List<Contributor> onPitch = canonicalOnPitch(home);
        if (onPitch.isEmpty()) return null;
        // No try/catch: a DB/concurrency failure must propagate and fail the advance so the
        // same slot is retried — it must never be swallowed into a phantom displayed goal.
        List<MatchEvent> events = svc.matchPlanService.resolveDueSlot(
                canonicalFixtureKey, competitionId, season, round, slotIndex, onPitch);
        Long scorerId = null;
        String scorerName = null;
        Long assistId = null;
        for (MatchEvent e : events) {
            if ("goal".equals(e.getEventType())) { scorerId = e.getPlayerId(); scorerName = e.getPlayerName(); }
            else if ("assist".equals(e.getEventType())) assistId = e.getPlayerId();
        }
        if (scorerId == null) return null;
        return new ResolvedGoal(scorerId, scorerName, assistId);
    }

    /** The Contributors currently on the pitch for a side: the live on-pitch set
     *  ({@code matchStates.isOnPitch}, which already reflects both AI and manual subs)
     *  matched to the persisted participant snapshot for their attributes. */
    private List<Contributor> canonicalOnPitch(boolean home) {
        long teamId = home ? teamId1 : teamId2;
        Set<Long> ids = home ? team1Ids : team2Ids;
        Map<Long, Contributor> byId = new HashMap<>();
        if (canonicalPlan != null) {
            for (LivePlanSnapshot.ParticipantView p : canonicalPlan.participants()) {
                if (p.teamId() == teamId) byId.put(p.contributor().playerId(), p.contributor());
            }
        }
        List<Contributor> out = new ArrayList<>();
        for (Long id : ids) {
            PlayerMatchState st = matchStates.get(id);
            if (st != null && st.isOnPitch) {
                Contributor c = byId.get(id);
                if (c == null) continue;
                // Field a substitute in the role he actually entered (inherited from the
                // player he replaced), not his natural bench position.
                String pos = fieldedPosition.get(id);
                out.add(pos != null && !pos.equals(c.position()) ? c.withPosition(pos) : c);
            }
        }
        return out;
    }

    /**
     * Append a canonical GOAL animation to the ordered {@link #canonicalAnimations} list,
     * stamped with its {@code (minute, slotIndex, fixtureKey)} so the frontend can queue and
     * play by {@code (minute, slotIndex)} and a refresh can identify each goal's animation.
     * Kept sorted by {@code (minute, slotIndex)}; multiple goals at one minute are preserved.
     */
    private void addCanonicalAnimation(int min, int slotIndex, GoalAnimationData anim) {
        if (anim == null) return;
        anim.setMinute(min);
        anim.setSlotIndex(slotIndex);
        anim.setFixtureKey(canonicalFixtureKey);
        // Persist the complete versioned recipe BEFORE exposing it to the frontend.  A cold
        // recovery then reuses these exact frames even after the generator implementation is
        // upgraded; it never silently regenerates an old goal with new code.
        svc.persistCanonicalAnimation(canonicalFixtureKey, slotIndex, anim);
        canonicalAnimations.add(anim);
        canonicalAnimations.sort(java.util.Comparator
                .comparingInt(GoalAnimationData::getMinute)
                .thenComparingInt(GoalAnimationData::getSlotIndex));
    }

    synchronized void loadPersistedCanonicalAnimations(List<GoalAnimationData> animations) {
        canonicalAnimations.clear();
        if (animations != null) canonicalAnimations.addAll(animations);
        canonicalAnimations.sort(java.util.Comparator
                .comparingInt(GoalAnimationData::getMinute)
                .thenComparingInt(GoalAnimationData::getSlotIndex));
    }

    /** Mark the session as committed — called by CompetitionController after
     *  all post-match work has run. Idempotent. */
    public synchronized void markCommitted() {
        this.committed = true;
    }

    /** Reset the in-memory "done" flags after a commit transaction ROLLED BACK, so a retry
     *  re-runs the finalization cleanly instead of seeing stale success. The durable truth is
     *  the plan status; these flags only mirror it. */
    public synchronized void resetForRetry() {
        this.committed = false;
        this.deferredArtifactsPersisted = false;
    }

    public synchronized boolean hasManualSubstitutions() {
        return !manualSubstitutions.isEmpty();
    }

    public synchronized List<ManualSubstitution> getManualSubstitutions() {
        return List.copyOf(manualSubstitutions);
    }

    // ---- Deferred context for /commit (stashed by simulateMatchday) ----
    double deferredTeamPower1, deferredTeamPower2;
    String deferredTactic1, deferredTactic2;
    PersonalizedTactic deferredPersonalizedTactic1;
    PersonalizedTactic deferredPersonalizedTactic2;
    boolean deferredKnockout;
    // Two-leg / bracket context so /commit can aggregate legs and propagate the
    // winner the same way the AI/batch path does in MatchRoundSimulator.
    // legNumber: 0 = single match, 1 = first leg, 2 = second leg.
    // tieId: 0 for single matches; links the two legs of a two-leg tie.
    // matchIndex: 1-based bracket slot for national-cup propagation (0 = N/A).
    int deferredLegNumber;
    long deferredTieId;
    int deferredMatchIndex;
    // Two-axis profiles + tactic vectors so /commit runs the knockout extra time on the same
    // attack-vs-defense model as the AI/batch path (null = scalar fallback).
    TacticalScoreService.TeamProfile deferredProfile1, deferredProfile2;
    TacticalScoreService.TacticVector deferredVector1, deferredVector2;

    public void setDeferredContext(double teamPower1, double teamPower2,
                                   String tactic1, String tactic2,
                                   PersonalizedTactic pt1,
                                   PersonalizedTactic pt2,
                                   boolean knockout,
                                   int legNumber, long tieId, int matchIndex) {
        this.deferredTeamPower1 = teamPower1;
        this.deferredTeamPower2 = teamPower2;
        this.deferredTactic1 = tactic1;
        this.deferredTactic2 = tactic2;
        this.deferredPersonalizedTactic1 = pt1;
        this.deferredPersonalizedTactic2 = pt2;
        this.deferredKnockout = knockout;
        this.deferredLegNumber = legNumber;
        this.deferredTieId = tieId;
        this.deferredMatchIndex = matchIndex;
    }

    /** Stash the two-axis profiles + tactic vectors used to score this match, so /commit can resolve
     *  a knockout tie's extra time on the same model. Pass null when the scalar engine is active. */
    public void setDeferredTwoAxis(TacticalScoreService.TeamProfile p1, TacticalScoreService.TacticVector v1,
                                   TacticalScoreService.TeamProfile p2, TacticalScoreService.TacticVector v2) {
        this.deferredProfile1 = p1;
        this.deferredVector1 = v1;
        this.deferredProfile2 = p2;
        this.deferredVector2 = v2;
    }

    public TacticalScoreService.TeamProfile getDeferredProfile1() { return deferredProfile1; }
    public TacticalScoreService.TeamProfile getDeferredProfile2() { return deferredProfile2; }
    public TacticalScoreService.TacticVector getDeferredVector1() { return deferredVector1; }
    public TacticalScoreService.TacticVector getDeferredVector2() { return deferredVector2; }

    public double getDeferredTeamPower1() { return deferredTeamPower1; }
    public double getDeferredTeamPower2() { return deferredTeamPower2; }
    public String getDeferredTactic1() { return deferredTactic1; }
    public String getDeferredTactic2() { return deferredTactic2; }
    public PersonalizedTactic getDeferredPersonalizedTactic1() { return deferredPersonalizedTactic1; }
    public PersonalizedTactic getDeferredPersonalizedTactic2() { return deferredPersonalizedTactic2; }
    public boolean isDeferredKnockout() { return deferredKnockout; }
    public int getDeferredLegNumber() { return deferredLegNumber; }
    public long getDeferredTieId() { return deferredTieId; }
    public int getDeferredMatchIndex() { return deferredMatchIndex; }

    /** Mutators for /commit's knockout extra-time decider. */
    public synchronized void bumpHomeScore() {
        this.homeScore++;
        refreshFullTimeEvent();
    }
    public synchronized void bumpAwayScore() {
        this.awayScore++;
        refreshFullTimeEvent();
    }

    public synchronized void applyCommitScore(int homeScore, int awayScore) {
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.homeShotsOnTarget = Math.max(this.homeShotsOnTarget, homeScore);
        this.awayShotsOnTarget = Math.max(this.awayShotsOnTarget, awayScore);
        this.homeShots = Math.max(this.homeShots, this.homeShotsOnTarget);
        this.awayShots = Math.max(this.awayShots, this.awayShotsOnTarget);
        refreshFullTimeEvent();
    }

    /** Snapshot the LiveMatchData DTO that's currently cached so /commit's
     *  callers can pass it to persistLiveMatchStats. */
    public LiveMatchData asLiveMatchData() { return snapshot(); }

    LiveMatchSession(LiveMatchSimulationService svc,
                     long teamId1, long teamId2,
                     double power1, double power2,
                     long competitionId, int season, int round,
                     boolean generateGoalAnimations) {
        this(svc, teamId1, teamId2, power1, power2, competitionId, season, round,
                generateGoalAnimations, null, new Random());
    }

    LiveMatchSession(LiveMatchSimulationService svc,
                     long teamId1, long teamId2,
                     double power1, double power2,
                     long competitionId, int season, int round,
                     boolean generateGoalAnimations,
                     Random random) {
        this(svc, teamId1, teamId2, power1, power2, competitionId, season, round,
                generateGoalAnimations, null, random);
    }

    LiveMatchSession(LiveMatchSimulationService svc,
                     long teamId1, long teamId2,
                     double power1, double power2,
                     long competitionId, int season, int round,
                     boolean generateGoalAnimations,
                     TacticalScoreService.Matchup matchup,
                     Random random) {
        this(svc, teamId1, teamId2, power1, power2, competitionId, season, round,
                generateGoalAnimations, matchup, random, -1, -1);
    }

    LiveMatchSession(LiveMatchSimulationService svc,
                     long teamId1, long teamId2,
                     double power1, double power2,
                     long competitionId, int season, int round,
                     boolean generateGoalAnimations,
                     TacticalScoreService.Matchup matchup,
                     Random random,
                     int targetHomeGoals, int targetAwayGoals) {
        this(svc, teamId1, teamId2, power1, power2, competitionId, season, round,
                generateGoalAnimations, matchup, random, targetHomeGoals, targetAwayGoals, false);
    }

    /**
     * Seeded constructor — used by determinism IT / fuzz tests to make a live
     * match reproducible given a fixed seed. Same (seed, config, inputs) →
     * identical event timeline + score.
     *
     * <p>When {@code matchup} is non-null (two-axis production engine), the per-minute possession and
     * attack chances are derived from the attack-vs-defense matchup (each side's effective attack vs
     * the other's defense, amplified by {@code ratioExponent}, modulated by openness + home bonus) so
     * the live scoreline tracks the same model as the instant engine. When null, the legacy scalar
     * power ratio drives the chances (flag OFF).
     */
    LiveMatchSession(LiveMatchSimulationService svc,
                     long teamId1, long teamId2,
                     double power1, double power2,
                     long competitionId, int season, int round,
                     boolean generateGoalAnimations,
                     TacticalScoreService.Matchup matchup,
                     Random random,
                     int targetHomeGoals, int targetAwayGoals,
                     boolean deferPersistenceUntilCommit) {
        this.svc = svc;
        this.teamId1 = teamId1;
        this.teamId2 = teamId2;
        this.competitionId = competitionId;
        this.season = season;
        this.round = round;
        this.generateGoalAnimations = generateGoalAnimations;
        this.random = random;
        this.deferPersistenceUntilCommit = deferPersistenceUntilCommit;

        this.homeTeamName = svc.teamRepository.findNameById(teamId1);
        this.awayTeamName = svc.teamRepository.findNameById(teamId2);
        this.competitionName = svc.competitionRepository.findNameById(competitionId);

        this.team1Outfield = svc.getOutfieldPlayers(teamId1);
        this.team2Outfield = svc.getOutfieldPlayers(teamId2);
        this.team1All = svc.humanRepository.findAllByTeamIdAndTypeId(teamId1, 1L).stream()
                .filter(h -> !h.isRetired()).collect(Collectors.toList());
        this.team2All = svc.humanRepository.findAllByTeamIdAndTypeId(teamId2, 1L).stream()
                .filter(h -> !h.isRetired()).collect(Collectors.toList());

        this.matchStates = new HashMap<>();
        Map<Long, PlayerSkills> skillsCache = svc.loadSkillsCache(team1All, team2All);
        svc.initializeMatchStates(matchStates, team1All, skillsCache);
        svc.initializeMatchStates(matchStates, team2All, skillsCache);
        this.team1Ids = team1All.stream().map(Human::getId).collect(Collectors.toSet());
        this.team2Ids = team2All.stream().map(Human::getId).collect(Collectors.toSet());

        this.goalAnimations = generateGoalAnimations ? new LinkedHashMap<>() : null;

        double rawRatio1, rawRatio2;
        if (matchup != null) {
            // Two-axis: chances come from the attack-vs-defense matchup, not a symmetric power ratio.
            MatchEngineConfig.TacticalModel cfg = svc.engineConfig.getTacticalModel();
            double exp = cfg.getRatioExponent();
            double a1 = Math.pow(matchup.effAtt1(), exp), d2 = Math.pow(matchup.effDef2(), exp);
            double a2 = Math.pow(matchup.effAtt2(), exp), d1 = Math.pow(matchup.effDef1(), exp);
            double atkRatio1 = a1 / (a1 + d2); // team1 attack effectiveness vs team2 defense
            double atkRatio2 = a2 / (a2 + d1); // team2 attack effectiveness vs team1 defense
            // Possession from each side's overall strength (effective attack + defense).
            double tot1 = matchup.effAtt1() + matchup.effDef1();
            double tot2 = matchup.effAtt2() + matchup.effDef2();
            double poss1 = (tot1 + tot2) > 0 ? tot1 / (tot1 + tot2) : 0.5;
            // An open game lifts both sides' chances; home side gets the attack bonus.
            double opennessFactor = cfg.getBaseOpenness() > 0 ? matchup.openness() / cfg.getBaseOpenness() : 1.0;
            double homeBonus = 1 + cfg.getHomeAttackBonus();
            this.team1PossChance = Math.min(0.65, poss1 + 0.03);
            this.team1AttackChance = Math.min(0.22, (0.04 + Math.min(0.14, atkRatio1 * 0.18)) * opennessFactor * homeBonus);
            this.team2AttackChance = Math.min(0.22, (0.04 + Math.min(0.14, atkRatio2 * 0.18)) * opennessFactor);
            rawRatio1 = atkRatio1;
            rawRatio2 = atkRatio2;
        } else {
            // Scalar fallback (flag OFF): symmetric power ratio.
            double totalPower = power1 + power2;
            double t1Poss = totalPower > 0 ? power1 / totalPower : 0.5;
            this.team1PossChance = Math.min(0.65, t1Poss + 0.03);
            rawRatio1 = totalPower > 0 ? power1 / totalPower : 0.5;
            rawRatio2 = 1.0 - rawRatio1;
            this.team1AttackChance = 0.04 + Math.min(0.14, rawRatio1 * 0.18);
            this.team2AttackChance = 0.04 + Math.min(0.14, rawRatio2 * 0.18);
        }

        int team1BigChances = svc.computeBigChances(rawRatio1, random);
        int team2BigChances = svc.computeBigChances(rawRatio2, random);
        this.team1BigChanceMinutes = svc.pickRandomMinutes(team1BigChances, 5, 89, random);
        this.team2BigChanceMinutes = svc.pickRandomMinutes(team2BigChances, 5, 89, random);
        team2BigChanceMinutes.removeAll(team1BigChanceMinutes);

        this.firstHalfStoppage = random.nextInt(6);
        this.secondHalfStoppage = random.nextInt(6);
        this.halfTimeMinute = 45 + firstHalfStoppage;
        this.totalMinutes = 90 + firstHalfStoppage + secondHalfStoppage;

        // Engine unification: when the instant scoreline is supplied, pre-schedule
        // exactly that many distinct goal minutes per team (same pattern as the
        // big-chance scheduling above). The attack branch forces a goal at these
        // minutes and never elsewhere, so the live final score == the instant one.
        this.targetHomeGoals = targetHomeGoals;
        this.targetAwayGoals = targetAwayGoals;
        this.pinned = targetHomeGoals >= 0 && targetAwayGoals >= 0;
        if (pinned) {
            // Pick all goal minutes from one shared pool so home and away never
            // collide on the same tick (each tick scores at most one team).
            Set<Integer> shared = svc.pickRandomMinutes(
                    targetHomeGoals + targetAwayGoals, 2, totalMinutes - 1, random);
            java.util.List<Integer> pool = new java.util.ArrayList<>(shared);
            Set<Integer> home = new java.util.LinkedHashSet<>();
            Set<Integer> away = new java.util.LinkedHashSet<>();
            for (int i = 0; i < pool.size(); i++) {
                if (i < targetHomeGoals) home.add(pool.get(i));
                else away.add(pool.get(i));
            }
            this.homeGoalMinutes = home;
            this.awayGoalMinutes = away;
        } else {
            this.homeGoalMinutes = java.util.Collections.emptySet();
            this.awayGoalMinutes = java.util.Collections.emptySet();
        }

        // Kickoff event — emitted once at session creation so the timeline
        // is non-empty even before the first advance.
        timeline.add(svc.createMinuteEvent(1, 0, 0, "kickoff",
                "The referee blows the whistle! " + homeTeamName + " vs " + awayTeamName + " is underway!",
                0, null, 0, null));
    }

    /**
     * Tick the engine forward up to {@code targetMinute} (clamped to
     * {@code totalMinutes}). Idempotent — calling with a minute already
     * past returns immediately. When the engine reaches full time on this
     * call, the full-time event is emitted. Legacy one-shot sessions persist
     * their events + fitness immediately; interactive deferred-commit sessions
     * leave that work for {@code /commit}.
     *
     * <p>Synchronized — guards against concurrent /advance and /substitute
     * calls from racing the same session.
     */
    synchronized void advanceUntil(int targetMinute) {
        int endMinute = Math.min(targetMinute, totalMinutes);
        if (currentMinute >= endMinute) return;

        // The ThreadLocal lives on goalAnimationService — set + cleared
        // around the tick so animations created mid-advance pick up the
        // first-half stoppage value, and so we don't leak the value to
        // unrelated work on this thread.
        svc.goalAnimationService.setMatchStoppage(firstHalfStoppage);
        try {
            while (currentMinute < endMinute) {
                currentMinute++;
                try {
                    tickOneMinute(currentMinute);
                } catch (RuntimeException e) {
                    // A failed tick (e.g. a canonical goal that could not be persisted) rolls
                    // the minute back so a retried advance re-runs it; already-emitted goals at
                    // that minute are skipped (emittedCanonicalSlots), so nothing double-counts.
                    currentMinute--;
                    throw e;
                }
            }
        } finally {
            svc.goalAnimationService.clearMatchStoppage();
        }

        if (currentMinute >= totalMinutes && !finished) {
            finished = true;
            refreshFullTimeEvent();
            if (!deferPersistenceUntilCommit) {
                persistDeferredArtifacts();
            }
        }

        // Canonical: persist a checkpoint (current minute + red cards) so a cold restart
        // RESUMES here rather than replaying from 0 and re-rolling those decisions.
        persistCanonicalCheckpoint();
    }

    private void persistCanonicalCheckpoint() {
        if (!isCanonicalPlanBound()) return;
        List<Long> reds = new ArrayList<>();
        for (PlayerMatchState state : matchStates.values()) {
            if (state.redCardMinute > 0) reds.add(state.playerId);
        }
        Long randomState = random instanceof CheckpointRandom checkpointRandom
                ? checkpointRandom.checkpointState() : null;
        svc.saveLiveCheckpoint(competitionId, season, round, teamId1, teamId2,
                currentMinute, reds, randomState, checkpoint());
    }

    private LiveSessionCheckpoint checkpoint() {
        LiveMatchData state = snapshot();
        // Canonical animations have their own durable, versioned rows. Repeating hundreds of
        // frames in a checkpoint written after every /advance would create needless DB churn.
        state.setGoalAnimations(null);
        state.setCanonicalAnimations(null);

        List<LiveSessionCheckpoint.PlayerState> players = new ArrayList<>(matchStates.size());
        for (PlayerMatchState player : matchStates.values()) {
            players.add(new LiveSessionCheckpoint.PlayerState(
                    player.playerId, player.currentStamina, player.minutesPlayed,
                    player.isOnPitch, player.yellowCardMinute, player.redCardMinute));
        }
        players.sort(Comparator.comparingLong(LiveSessionCheckpoint.PlayerState::playerId));

        List<LiveSessionCheckpoint.ManualSubstitutionState> manualSubs = manualSubstitutions.stream()
                .map(sub -> new LiveSessionCheckpoint.ManualSubstitutionState(
                        sub.teamId(), sub.playerOutId(), sub.playerInId(), sub.minute()))
                .toList();
        return new LiveSessionCheckpoint(state, new ArrayList<>(dbEvents), players,
                new HashMap<>(fieldedPosition), homePossessionMinutes,
                homeLastSubMin, awayLastSubMin, manualSubs);
    }

    /**
     * Restore a cold-recovered session to its crash-minute state (RESUME, not replay): set the
     * score from the canonical slots already resolved by that minute (marking them emitted so a
     * resumed advance does not re-emit them), apply the substitutions and red cards recorded up
     * to then, and set the current minute. Future advance continues from here against the
     * correct on-pitch set, so unresolved future slots resolve against the right candidates.
     */
    synchronized void restoreCheckpoint(int minute, List<Long> redCardIds, Long randomState,
                                        LiveSessionCheckpoint checkpoint) {
        if (minute <= 0 || canonicalPlan == null) return;
        if (checkpoint != null && checkpoint.state() != null) {
            restoreVisibleState(checkpoint.state());
            dbEvents.clear();
            if (checkpoint.pendingEvents() != null) dbEvents.addAll(checkpoint.pendingEvents());
            if (checkpoint.players() != null && !checkpoint.players().isEmpty()) {
                restoreExactEngineState(checkpoint);
            }
        }
        // Score (+ mark emitted) from resolved slots (regular OR extra time) up to the checkpoint.
        // When a full checkpoint exists its score is already restored; only rebuild it for saves
        // created before full-state checkpoints were introduced.
        if (checkpoint == null || checkpoint.state() == null) {
        for (LivePlanSnapshot.SlotView slot : canonicalPlan.slots()) {
            if (slot.resolved() && slot.minute() <= minute) {
                emittedCanonicalSlots.add(slot.slotIndex());
                if (slot.teamId() == teamId1) homeScore++; else awayScore++;
            }
        }
        } else {
            for (LivePlanSnapshot.SlotView slot : canonicalPlan.slots()) {
                if (slot.resolved() && slot.minute() <= minute) emittedCanonicalSlots.add(slot.slotIndex());
            }
        }
        boolean hasExactPlayerState = checkpoint != null && checkpoint.players() != null
                && !checkpoint.players().isEmpty();
        // Legacy checkpoints need the persisted substitutions replayed. A full checkpoint
        // already contains the exact pitch/bench state and fielded roles, so replaying would
        // double the counters/timeline and can overwrite the role inherited by a substitute.
        if (!hasExactPlayerState) {
            for (int m = 1; m <= minute; m++) {
                List<RecordedSub> due = subsToReplay.get(m);
                if (due != null) {
                    for (RecordedSub s : due) applyReplaySub(s);
                }
            }
        }
        subsToReplay.keySet().removeIf(m -> m <= minute);
        // Apply red cards — those players are off the pitch for future slot resolution.
        if (!hasExactPlayerState && redCardIds != null) {
            for (Long id : redCardIds) {
                PlayerMatchState s = matchStates.get(id);
                if (s != null) { s.isOnPitch = false; s.redCardMinute = minute; }
            }
        }
        if (randomState != null) {
            CheckpointRandom restored = new CheckpointRandom(0L);
            restored.restoreCheckpointState(randomState);
            this.random = restored;
        }
        this.currentMinute = minute;
    }

    private void restoreExactEngineState(LiveSessionCheckpoint checkpoint) {
        if (checkpoint.players() != null) {
            for (LiveSessionCheckpoint.PlayerState saved : checkpoint.players()) {
                PlayerMatchState player = matchStates.get(saved.playerId());
                if (player == null) continue;
                player.currentStamina = saved.currentStamina();
                player.minutesPlayed = saved.minutesPlayed();
                player.isOnPitch = saved.onPitch();
                player.yellowCardMinute = saved.yellowCardMinute();
                player.redCardMinute = saved.redCardMinute();
            }
        }
        if (checkpoint.fieldedPositions() != null && !checkpoint.fieldedPositions().isEmpty()) {
            fieldedPosition.clear();
            fieldedPosition.putAll(checkpoint.fieldedPositions());
        }
        homePossessionMinutes = checkpoint.homePossessionMinutes();
        homeLastSubMin = checkpoint.homeLastSubMinute();
        awayLastSubMin = checkpoint.awayLastSubMinute();
        manualSubstitutions.clear();
        if (checkpoint.manualSubstitutions() != null) {
            for (LiveSessionCheckpoint.ManualSubstitutionState sub : checkpoint.manualSubstitutions()) {
                manualSubstitutions.add(new ManualSubstitution(
                        sub.teamId(), sub.playerOutId(), sub.playerInId(), sub.minute()));
            }
        }
    }

    private void restoreVisibleState(LiveMatchData data) {
        homeScore = data.getHomeScore(); awayScore = data.getAwayScore();
        homeShots = data.getHomeShots(); awayShots = data.getAwayShots();
        homeShotsOnTarget = data.getHomeShotsOnTarget(); awayShotsOnTarget = data.getAwayShotsOnTarget();
        homeCorners = data.getHomeCorners(); awayCorners = data.getAwayCorners();
        homeFouls = data.getHomeFouls(); awayFouls = data.getAwayFouls();
        homeYellowCards = data.getHomeYellowCards(); awayYellowCards = data.getAwayYellowCards();
        homeRedCards = data.getHomeRedCards(); awayRedCards = data.getAwayRedCards();
        homeOffsides = data.getHomeOffsides(); awayOffsides = data.getAwayOffsides();
        int elapsed = Math.max(1, data.getCurrentMinute());
        homePossessionMinutes = (int) Math.round(elapsed * data.getHomePossession() / 100.0);
        firstHalfStoppage = data.getFirstHalfStoppage();
        secondHalfStoppage = data.getSecondHalfStoppage();
        halfTimeMinute = 45 + firstHalfStoppage;
        homeSubsUsed = Math.max(0, 3 - data.getHomeSubsRemaining());
        awaySubsUsed = Math.max(0, 3 - data.getAwaySubsRemaining());
        finished = data.isFinished();
        timeline.clear();
        if (data.getTimeline() != null) timeline.addAll(data.getTimeline());
        staminaSnapshots.clear();
        if (data.getStaminaSnapshots() != null) staminaSnapshots.addAll(data.getStaminaSnapshots());
        if (goalAnimations != null) {
            goalAnimations.clear();
            if (data.getGoalAnimations() != null) goalAnimations.putAll(data.getGoalAnimations());
        }
        restorePlayers(data.getHomePitch(), true);
        restorePlayers(data.getHomeBench(), false);
        restorePlayers(data.getAwayPitch(), true);
        restorePlayers(data.getAwayBench(), false);
    }

    private void restorePlayers(List<PlayerStaminaInfo> players, boolean onPitch) {
        if (players == null) return;
        for (PlayerStaminaInfo info : players) {
            PlayerMatchState state = matchStates.get(info.getPlayerId());
            if (state == null) continue;
            state.currentStamina = info.getStamina();
            state.minutesPlayed = info.getMinutesPlayed();
            state.isOnPitch = onPitch;
            state.yellowCardMinute = info.getYellowCardMinute();
            state.redCardMinute = info.getRedCardMinute();
        }
    }

    private void refreshFullTimeEvent() {
        if (!finished) return;
        LiveMatchMinute fullTime = svc.createMinuteEvent(totalMinutes, homeScore, awayScore, "full_time",
                "FULL TIME! " + homeTeamName + " " + homeScore + " - " + awayScore + " " + awayTeamName,
                0, null, 0, null);
        for (int i = timeline.size() - 1; i >= 0; i--) {
            if ("full_time".equals(timeline.get(i).getEventType())) {
                timeline.set(i, fullTime);
                return;
            }
        }
        timeline.add(fullTime);
    }

    public synchronized List<MatchEvent> nonGoalDbEvents() {
        return dbEvents.stream()
                .filter(e -> !"goal".equals(e.getEventType()))
                .filter(e -> !"assist".equals(e.getEventType()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public synchronized void persistDeferredArtifacts() {
        persistDeferredArtifacts(new ArrayList<>(dbEvents));
    }

    public synchronized void persistDeferredArtifacts(List<MatchEvent> eventsToPersist) {
        if (deferredArtifactsPersisted) return;
        if (!finished) {
            throw new IllegalStateException("Cannot persist deferred live-match artifacts before full time.");
        }
        if (eventsToPersist != null && !eventsToPersist.isEmpty()) {
            svc.matchEventRepository.saveAll(eventsToPersist);
        }
        svc.persistPostMatchFitness(matchStates, team1All, team2All);
        deferredArtifactsPersisted = true;
    }

    /** Count players from {@code teamIds} currently on the pitch. */
    private int countOnPitch(Set<Long> teamIds) {
        int count = 0;
        for (Long id : teamIds) {
            PlayerMatchState s = matchStates.get(id);
            if (s != null && s.isOnPitch) count++;
        }
        return count;
    }

    private void tickOneMinute(int min) {
        // Canonical plan bound: score exactly the plan's goal slots due this minute
        // (both sides, slotIndex order), resolved against the live on-pitch set. The
        // pinned-forcing sets are empty in this mode, so the narration below only adds
        // cosmetic events (possession/shots/cards) around these fixed goals.
        if (isCanonicalPlanBound()) {
            emitCanonicalGoalsAt(min);
            // Cold recovery: replay the substitutions recorded before a restart at their
            // minute, so the recovered on-pitch set matches the persisted timeline.
            List<RecordedSub> dueSubs = subsToReplay.get(min);
            if (dueSubs != null) {
                for (RecordedSub s : dueSubs) applyReplaySub(s);
            }
        }

        // A scheduled (pinned) goal minute forces that team to attack AND score —
        // it takes priority over a big chance so the pinned scoreline always lands.
        boolean isHomeGoalMinute = homeGoalMinutes.contains(min);
        boolean isAwayGoalMinute = awayGoalMinutes.contains(min);
        boolean isHomeBigChance = isHomeGoalMinute || team1BigChanceMinutes.contains(min);
        boolean isAwayBigChance = isAwayGoalMinute || team2BigChanceMinutes.contains(min);
        // A scheduled goal minute pins the ball to that team (overriding any
        // coincident big chance for the other side). Goal minutes are disjoint
        // across teams, so at most one of these fires.
        if (isHomeGoalMinute) isAwayBigChance = false;
        if (isAwayGoalMinute) isHomeBigChance = false;
        boolean forcedGoal = isHomeGoalMinute || isAwayGoalMinute;
        boolean forcedAttack = isHomeBigChance || isAwayBigChance;

        // Man-advantage adjustment: a short-handed team (red card) sees less
        // of the ball and creates fewer chances. The opponent benefits from
        // the extra possession; their attack chance is left untouched since
        // they already get more attacking rolls per minute.
        int team1OnPitch = countOnPitch(team1Ids);
        int team2OnPitch = countOnPitch(team2Ids);
        int manDiff = team1OnPitch - team2OnPitch; // + = team1 advantage
        double effectivePossChance = team1PossChance;
        if (manDiff != 0) {
            effectivePossChance = Math.max(0.15, Math.min(0.85, team1PossChance + 0.08 * manDiff));
        }

        boolean team1HasBall = forcedAttack
                ? isHomeBigChance
                : random.nextDouble() < effectivePossChance;
        if (team1HasBall) homePossessionMinutes++;

        long attackingTeamId = team1HasBall ? teamId1 : teamId2;
        String attackingTeamName = team1HasBall ? homeTeamName : awayTeamName;
        List<Human> attackers = svc.filterOnPitch(team1HasBall ? team1Outfield : team2Outfield, matchStates);
        if (attackers.isEmpty()) attackers = team1HasBall ? team1Outfield : team2Outfield;
        List<Human> allDefenders = svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates);
        if (allDefenders.isEmpty()) allDefenders = team1HasBall ? team2All : team1All;

        double roll = random.nextDouble();
        double currentAttackChance;
        if (forcedAttack) {
            currentAttackChance = 1.0;
        } else {
            double base = team1HasBall ? team1AttackChance : team2AttackChance;
            int attackerOnPitch = team1HasBall ? team1OnPitch : team2OnPitch;
            currentAttackChance = base * svc.manAdvantageAttackMultiplier(attackerOnPitch);
        }
        double attackEnd     = currentAttackChance;
        double possessionEnd = attackEnd + 0.38;
        double foulEnd       = possessionEnd + 0.10;
        double offsideEnd    = foulEnd + 0.04;

        if (roll < attackEnd) {
            tickAttackBranch(min, attackers, attackingTeamId, attackingTeamName, team1HasBall, forcedAttack, forcedGoal);
        } else if (roll < possessionEnd) {
            tickPossessionBranch(min, attackingTeamId, attackingTeamName);
        } else if (roll < foulEnd) {
            tickFoulBranch(min, allDefenders, team1HasBall);
        } else if (roll < offsideEnd) {
            tickOffsideBranch(min, attackers, attackingTeamId, attackingTeamName, team1HasBall);
        } else {
            tickBuildupBranch(min, attackingTeamId, attackingTeamName);
        }

        // Reactive AI substitutions for both sides.
        tickAiSubsForTeam(min, true);
        tickAiSubsForTeam(min, false);

        // Stamina tick + snapshot — tempo from deferred tactics (set by
        // CompetitionController right after createInteractiveSession). Falls
        // back to Standard tempo when tactics weren't stashed (legacy path).
        double tempoMult1 = svc.tempoMultiplier(
                deferredPersonalizedTactic1 != null ? deferredPersonalizedTactic1.getTempo() : null);
        double tempoMult2 = svc.tempoMultiplier(
                deferredPersonalizedTactic2 != null ? deferredPersonalizedTactic2.getTempo() : null);
        svc.applyStaminaTick(matchStates, team1Ids, team2Ids, tempoMult1, tempoMult2);
        if (min % LiveMatchSimulationService.STAMINA_SNAPSHOT_INTERVAL == 0 || min == totalMinutes) {
            staminaSnapshots.add(svc.captureStaminaSnapshot(min, matchStates, team1Ids, team2Ids));
        }

        // Half time
        if (min == halfTimeMinute) {
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "half_time",
                    "HALF TIME! " + homeTeamName + " " + homeScore + " - " + awayScore + " " + awayTeamName,
                    0, null, 0, null));
        }
    }

    /** Goal / save / miss / blocked / corner outcomes. Picks the shooter,
     *  rolls the outcome, updates score+shot counters, emits timeline +
     *  dbEvents + (optionally) goal animations. */
    private void tickAttackBranch(int min, List<Human> attackers,
                                   long attackingTeamId, String attackingTeamName,
                                   boolean team1HasBall, boolean forcedAttack, boolean forcedGoal) {
        if (attackers.isEmpty()) {
            // A pinned goal can't be dropped — if the attacking squad somehow
            // has no eligible outfield player, fall back to the full squad so
            // the scheduled goal still lands and the final score stays exact.
            if (forcedGoal) attackers = team1HasBall ? team1All : team2All;
            if (attackers.isEmpty()) return;
        }
        Human attacker = svc.pickWeightedAttacker(attackers, matchStates, min, random);
        if (team1HasBall) homeShots++; else awayShots++;

        double attackRoll = random.nextDouble();
        // Pinned mode: this tick MUST score (forcedGoal) or MUST NOT score
        // (every other tick) so the live final equals the instant scoreline.
        // We keep the same roll draw (RNG parity) but reshape the cutoffs:
        //   forcedGoal  → goal cutoff = 1.0 (always GOAL)
        //   pinned, not forced → goal cutoff = 0.0 (never GOAL; SAVE/MISS/etc.)
        double goalCutoff;
        if (forcedGoal) goalCutoff = 1.0;
        else if (pinned) goalCutoff = 0.0;
        else goalCutoff = forcedAttack ? 0.30 : 0.17;
        // When goals are suppressed (pinned, not forced), redistribute the freed
        // probability mass across save/miss/blocked/corner so flavor stays varied.
        double saveCutoff    = forcedAttack ? 0.60 : 0.42;
        double missCutoff    = forcedAttack ? 0.85 : 0.70;
        double blockedCutoff = forcedAttack ? 0.95 : 0.87;

        if (attackRoll < goalCutoff) {
            String playType = svc.pickAttackPlayType("GOAL", random);
            if (team1HasBall) { homeScore++; homeShotsOnTarget++; }
            else { awayScore++; awayShotsOnTarget++; }

            String goalDesc = LiveMatchSimulationService.GOAL_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.GOAL_DESCRIPTIONS.length)];
            String prefix = svc.playTypePrefix(playType, "GOAL");
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "goal",
                    prefix + "GOAL! " + attacker.getName() + "! " + goalDesc,
                    attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "goal", attacker.getId(), attacker.getName(), attackingTeamId,
                    (prefix.isEmpty() ? "" : prefix.trim() + " ") + goalDesc));

            Human goalAssister = null;
            if (random.nextDouble() < 0.7 && attackers.size() > 1) {
                goalAssister = svc.pickDifferentPlayer(attackers, attacker, matchStates, random);
                if (goalAssister != null) {
                    dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                            min, "assist", goalAssister.getId(), goalAssister.getName(), attackingTeamId, "Assist"));
                }
            }

            if (goalAnimations != null) {
                GoalAnimationData anim = svc.buildAttackAnimation(
                        svc.filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                        svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                        attacker, goalAssister,
                        attackingTeamId, team1HasBall ? teamId2 : teamId1,
                        teamId1, min, "GOAL", playType);
                if (anim != null) goalAnimations.put(min, anim);
            }

        } else if (attackRoll < saveCutoff) {
            String playType = svc.pickAttackPlayType("SAVE", random);
            if (team1HasBall) homeShotsOnTarget++; else awayShotsOnTarget++;
            String saveDesc = LiveMatchSimulationService.SAVE_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.SAVE_DESCRIPTIONS.length)];
            String prefix = svc.playTypePrefix(playType, "SAVE");
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "shot_saved",
                    prefix + attacker.getName() + " " + saveDesc,
                    attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));
            // Persist save events so the Match Events summary can show
            // "penalty saved" / "free kick saved" alongside goals + cards.
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "shot_saved", attacker.getId(), attacker.getName(), attackingTeamId,
                    (prefix.isEmpty() ? "Shot saved" : prefix.trim())));

            boolean shouldAnimate = forcedAttack || (goalAnimations != null && random.nextDouble() < 0.25);
            if (goalAnimations != null && shouldAnimate) {
                GoalAnimationData anim = svc.buildAttackAnimation(
                        svc.filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                        svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                        attacker, null,
                        attackingTeamId, team1HasBall ? teamId2 : teamId1,
                        teamId1, min, "SAVE", playType);
                if (anim != null) goalAnimations.put(min, anim);
            }

        } else if (attackRoll < missCutoff) {
            String playType = svc.pickAttackPlayType("MISS", random);
            String missDesc = LiveMatchSimulationService.MISS_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.MISS_DESCRIPTIONS.length)];
            String prefix = svc.playTypePrefix(playType, "MISS");
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "shot_wide",
                    prefix + attacker.getName() + " " + missDesc,
                    attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "shot_wide", attacker.getId(), attacker.getName(), attackingTeamId,
                    (prefix.isEmpty() ? "Shot wide" : prefix.trim())));

            boolean shouldAnimate = forcedAttack || (goalAnimations != null && random.nextDouble() < 0.15);
            if (goalAnimations != null && shouldAnimate) {
                GoalAnimationData anim = svc.buildAttackAnimation(
                        svc.filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                        svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                        attacker, null,
                        attackingTeamId, team1HasBall ? teamId2 : teamId1,
                        teamId1, min, "MISS", playType);
                if (anim != null) goalAnimations.put(min, anim);
            }

        } else if (attackRoll < blockedCutoff) {
            String blockDesc = LiveMatchSimulationService.BLOCK_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.BLOCK_DESCRIPTIONS.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "shot_blocked",
                    attacker.getName() + blockDesc,
                    attacker.getId(), attacker.getName(), attackingTeamId, attackingTeamName));

        } else {
            // Corner
            if (team1HasBall) homeCorners++; else awayCorners++;
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "corner",
                    "Corner kick for " + attackingTeamName + ".",
                    0, null, attackingTeamId, attackingTeamName));

            // Corner-header goal — suppressed in pinned mode (goals only land at
            // scheduled minutes, which route through the GOAL branch above).
            if (!pinned && random.nextDouble() < 0.08 && !attackers.isEmpty()) {
                Human header = svc.pickWeightedAttacker(attackers, matchStates, min, random);
                if (team1HasBall) { homeScore++; homeShotsOnTarget++; homeShots++; }
                else { awayScore++; awayShotsOnTarget++; awayShots++; }

                timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "goal",
                        "GOAL! " + header.getName() + " rises highest and heads it in from the corner!",
                        header.getId(), header.getName(), attackingTeamId, attackingTeamName));
                dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                        min, "goal", header.getId(), header.getName(), attackingTeamId, "Header from corner"));

                if (goalAnimations != null) {
                    GoalAnimationData anim = svc.goalAnimationService.generate(
                            svc.filterOnPitch(team1HasBall ? team1All : team2All, matchStates),
                            svc.filterOnPitch(team1HasBall ? team2All : team1All, matchStates),
                            header, null,
                            attackingTeamId, team1HasBall ? teamId2 : teamId1,
                            teamId1, min, "GOAL");
                    if (anim != null) goalAnimations.put(min, anim);
                }
            }
        }
    }

    /** Sparse "team holding the ball" commentary; only every 5 minutes (and
     *  at kickoff) to avoid spamming the feed. */
    private void tickPossessionBranch(int min, long attackingTeamId, String attackingTeamName) {
        if (min % 5 == 0 || min == 1) {
            String template = LiveMatchSimulationService.POSSESSION_COMMENTARY[
                    random.nextInt(LiveMatchSimulationService.POSSESSION_COMMENTARY.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "commentary",
                    String.format(template, attackingTeamName),
                    0, null, attackingTeamId, attackingTeamName));
        }
    }

    /** Foul + card resolution. Pace-weighted fouler pick; 22% yellow, 2% red,
     *  rest are uncommented fouls (gated by min%4 to keep the feed quiet).
     *  Red card flips the player's isOnPitch off so subsequent ticks
     *  exclude them from action picking + animations. */
    private void tickFoulBranch(int min, List<Human> allDefenders, boolean team1HasBall) {
        if (allDefenders.isEmpty()) return;
        Human fouler = svc.pickFouler(allDefenders, matchStates, random);
        if (fouler == null) fouler = allDefenders.get(random.nextInt(allDefenders.size()));
        if (team1HasBall) awayFouls++; else homeFouls++;
        long foulerTeamId = team1HasBall ? teamId2 : teamId1;
        String foulerTeamName = team1HasBall ? awayTeamName : homeTeamName;
        double cardRoll = random.nextDouble();
        if (cardRoll < 0.22) {
            if (team1HasBall) awayYellowCards++; else homeYellowCards++;
            String foulDesc = LiveMatchSimulationService.FOUL_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.FOUL_DESCRIPTIONS.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "yellow_card",
                    fouler.getName() + " " + foulDesc + " Yellow card!",
                    fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "yellow_card", fouler.getId(), fouler.getName(), foulerTeamId, "Yellow card"));
            PlayerMatchState ycState = matchStates.get(fouler.getId());
            // First yellow only — the per-player snapshot just needs to know
            // they're carrying one. A second yellow would normally promote to
            // red, but the engine handles red as a separate path below.
            if (ycState != null && ycState.yellowCardMinute == 0) {
                ycState.yellowCardMinute = min;
            }
        } else if (cardRoll < 0.24) {
            if (team1HasBall) awayRedCards++; else homeRedCards++;
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "red_card",
                    "RED CARD! " + fouler.getName() + " is sent off for a terrible challenge!",
                    fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    min, "red_card", fouler.getId(), fouler.getName(), foulerTeamId, "Red card"));
            PlayerMatchState rcState = matchStates.get(fouler.getId());
            if (rcState != null) {
                rcState.isOnPitch = false;
                rcState.redCardMinute = min;
            }
        } else if (min % 4 == 0) {
            String foulDesc = LiveMatchSimulationService.FOUL_DESCRIPTIONS[
                    random.nextInt(LiveMatchSimulationService.FOUL_DESCRIPTIONS.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "foul",
                    fouler.getName() + " " + foulDesc,
                    fouler.getId(), fouler.getName(), foulerTeamId, foulerTeamName));
        }
    }

    /** Offside whistle; throttled to one event every 3 minutes to keep the
     *  feed readable when the engine rolls offsides repeatedly. */
    private void tickOffsideBranch(int min, List<Human> attackers,
                                    long attackingTeamId, String attackingTeamName,
                                    boolean team1HasBall) {
        if (attackers.isEmpty()) return;
        Human offside = attackers.get(random.nextInt(attackers.size()));
        if (team1HasBall) homeOffsides++; else awayOffsides++;
        if (min % 3 == 0) {
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "offside",
                    offside.getName() + " is caught offside. Free kick to the defence.",
                    offside.getId(), offside.getName(), attackingTeamId, attackingTeamName));
        }
    }

    /** Generic "build-up play" commentary fallback; every 7 minutes so the
     *  feed picks up some chatter even on quiet stretches. */
    private void tickBuildupBranch(int min, long attackingTeamId, String attackingTeamName) {
        if (min % 7 == 0) {
            String template = LiveMatchSimulationService.BUILDUP_COMMENTARY[
                    random.nextInt(LiveMatchSimulationService.BUILDUP_COMMENTARY.length)];
            timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "commentary",
                    String.format(template, attackingTeamName),
                    0, null, attackingTeamId, attackingTeamName));
        }
    }

    /** Reactive AI substitutions for one side, parameterised by team. Caps
     *  at 3 subs per side and enforces ≥8 minutes between subs. Falls back
     *  to a FATIGUE swap if the originally-decided reason (OFFENSIVE /
     *  DEFENSIVE) can't find an eligible swap. */
    private void tickAiSubsForTeam(int min, boolean isHome) {
        int subsUsed   = isHome ? homeSubsUsed : awaySubsUsed;
        int lastSubMin = isHome ? homeLastSubMin : awayLastSubMin;
        List<Human> squad = isHome ? team1All : team2All;
        if (subsUsed >= 3 || min - lastSubMin < 8 || squad.size() <= 11) return;

        Set<Long> teamIds = isHome ? team1Ids : team2Ids;
        int ownScore = isHome ? homeScore : awayScore;
        int oppScore = isHome ? awayScore : homeScore;
        long teamId = isHome ? teamId1 : teamId2;
        String teamName = isHome ? homeTeamName : awayTeamName;

        SubReason reason = svc.decideSubReason(min, ownScore, oppScore, matchStates, teamIds);
        if (reason == null) return;

        SubSwap swap = svc.performSub(matchStates, squad, reason);
        if (swap == null && reason != SubReason.FATIGUE) {
            swap = svc.performSub(matchStates, squad, SubReason.FATIGUE);
            if (swap != null) reason = SubReason.FATIGUE;
        }
        if (swap == null) return;

        // Persist FIRST (no swallow); if it fails, revert the on-pitch swap performSub made
        // so the AI substitution simply did not happen this tick.
        if (isCanonicalPlanBound()) {
            try {
                recordCanonicalSub(teamId, min, swap.off.getId(), swap.on.getId());
            } catch (RuntimeException e) {
                PlayerMatchState offSt = matchStates.get(swap.off.getId());
                PlayerMatchState onSt = matchStates.get(swap.on.getId());
                if (offSt != null) offSt.isOnPitch = true;
                if (onSt != null) onSt.isOnPitch = false;
                return;
            }
        }

        if (isHome) { homeSubsUsed++; homeLastSubMin = min; }
        else        { awaySubsUsed++; awayLastSubMin = min; }

        String commentary = svc.buildSubCommentary(teamName, swap, reason);
        timeline.add(svc.createMinuteEvent(min, homeScore, awayScore, "substitution",
                commentary, swap.off.getId(), swap.off.getName(), teamId, teamName));
        dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                min, "substitution", swap.off.getId(), swap.off.getName(), teamId,
                reason.name() + " | Off: " + swap.off.getName() + " | On: " + swap.on.getName()));
    }

    /** Record a substitution (AI or manual) into the canonical timeline. Persists FIRST
     *  and propagates any failure (callers must treat a failed record as a rejected sub —
     *  no swallow), then tracks the entering player's fielded position (he inherits the
     *  role of the player he replaces) so the resolver weights him correctly. */
    private void recordCanonicalSub(long teamId, int minute, long offId, long onId) {
        if (!isCanonicalPlanBound() || svc.matchPlanService == null || canonicalFixtureKey == null) return;
        svc.matchPlanService.recordLiveSubstitution(canonicalFixtureKey, teamId, minute, offId, onId);
        String enteringPos = fieldedPosition.get(offId);
        if (enteringPos != null) fieldedPosition.put(onId, enteringPos);
    }

    /**
     * Apply a manager-driven substitution. Validates everything the
     * endpoint can validate and throws {@link InvalidSubstitutionException}
     * with a human-readable message on any failure. On success, toggles
     * the on-pitch flags, bumps the per-side counter, and adds a manual
     * sub event to the timeline.
     *
     * <p>The team side is derived from {@code playerOutId} — caller does
     * not have to pass it, and we reject mixing players across teams.
     */
    synchronized void applyUserSub(long playerOutId, long playerInId) {
        if (finished) {
            throw new InvalidSubstitutionException("Match already finished. Commit the result instead.");
        }
        if (playerOutId == playerInId) throw new InvalidSubstitutionException("Same player on both ends of the swap.");

        PlayerMatchState off = matchStates.get(playerOutId);
        PlayerMatchState on = matchStates.get(playerInId);
        if (off == null) throw new InvalidSubstitutionException("Player coming off is not in either squad.");
        if (on == null) throw new InvalidSubstitutionException("Player coming on is not in either squad.");

        boolean isHome;
        if (team1Ids.contains(playerOutId) && team1Ids.contains(playerInId)) isHome = true;
        else if (team2Ids.contains(playerOutId) && team2Ids.contains(playerInId)) isHome = false;
        else throw new InvalidSubstitutionException("Both players must belong to the same team.");

        int subsUsed = isHome ? homeSubsUsed : awaySubsUsed;
        if (subsUsed >= 3) throw new InvalidSubstitutionException("No substitutions remaining.");
        if (!off.isOnPitch) throw new InvalidSubstitutionException("Player " + off.name + " is not on the pitch.");
        if (on.isOnPitch) throw new InvalidSubstitutionException("Player " + on.name + " is already on the pitch.");

        // GK can only be swapped with a GK; otherwise either side becomes
        // GK-less or has two GKs.
        boolean offIsGk = "GK".equals(off.position);
        boolean onIsGk = "GK".equals(on.position);
        if (offIsGk != onIsGk) {
            throw new InvalidSubstitutionException("GK can only be swapped with another GK.");
        }

        long subTeamId = isHome ? teamId1 : teamId2;
        int subMinute = Math.max(1, lastSubAtMinute > 0 ? lastSubAtMinute : currentMinute);

        // Persist the substitution FIRST (atomic): the user's move is only applied once it
        // is durably recorded in the canonical timeline. A persistence failure rejects the
        // substitution before any in-memory state changes — never a swallowed best-effort.
        if (isCanonicalPlanBound()) {
            try {
                recordCanonicalSub(subTeamId, subMinute, playerOutId, playerInId);
            } catch (RuntimeException e) {
                throw new InvalidSubstitutionException("Could not record the substitution; try again.");
            }
        }

        // Apply
        off.isOnPitch = false;
        on.isOnPitch = true;
        if (isHome) { homeSubsUsed++; homeLastSubMin = currentMinute; }
        else        { awaySubsUsed++; awayLastSubMin = currentMinute; }

        // Build Human refs for the event
        Human offHuman = svc.findById(isHome ? team1All : team2All, off.playerId);
        Human onHuman  = svc.findById(isHome ? team1All : team2All, on.playerId);
        if (offHuman != null && onHuman != null) {
            SubSwap swap = new SubSwap(offHuman, onHuman);
            String teamName = isHome ? homeTeamName : awayTeamName;
            long teamId = isHome ? teamId1 : teamId2;
            String commentary = svc.buildSubCommentary(teamName, swap, SubReason.MANUAL);
            int eventMinute = Math.max(1, lastSubAtMinute > 0 ? lastSubAtMinute : currentMinute);

            LiveMatchMinute subEvent = svc.createMinuteEvent(eventMinute, homeScore, awayScore, "substitution",
                    commentary, offHuman.getId(), offHuman.getName(), teamId, teamName);
            // Insert chronologically — find the last existing event with
            // minute <= eventMinute and insert right after it. The pre-baked
            // timeline already covers minutes 1..totalMinutes so the user's
            // sub at min 60 needs to land between the existing min-60 events
            // and the min-61 events, not at the end.
            int insertAt = timeline.size();
            for (int i = 0; i < timeline.size(); i++) {
                if (timeline.get(i).getMinute() > eventMinute) { insertAt = i; break; }
            }
            timeline.add(insertAt, subEvent);
            manualSubstitutions.add(new ManualSubstitution(teamId, offHuman.getId(), onHuman.getId(), eventMinute));

            dbEvents.add(svc.buildMatchEvent(competitionId, season, round, teamId1, teamId2,
                    eventMinute, "substitution", offHuman.getId(), offHuman.getName(), teamId,
                    "MANUAL | Off: " + offHuman.getName() + " | On: " + onHuman.getName()));
            // Canonical recording already happened atomically before the on-pitch mutation.
        }
        // A manual substitution happens between /advance calls. Persist its full post-sub state
        // immediately; otherwise a crash before the next advance would recover the persisted
        // substitution together with a pre-sub checkpoint and could expose the wrong XI.
        persistCanonicalCheckpoint();
    }

    /** Optional override for the minute at which a manual sub should be
     *  recorded. Set by the controller from the request body and consumed
     *  by the next applyUserSub call. Reset to -1 between calls. */
    private int lastSubAtMinute = -1;

    public synchronized LiveMatchData applyUserSubAtMinuteAndSnapshot(long playerOutId, long playerInId, int atMinute) {
        this.lastSubAtMinute = Math.max(1, atMinute);
        try {
            return applyUserSubAndSnapshot(playerOutId, playerInId);
        } finally {
            this.lastSubAtMinute = -1;
        }
    }

    /** Combined advance + snapshot in a single critical section. */
    public synchronized LiveMatchData advanceUntilAndSnapshot(int targetMinute) {
        advanceUntil(targetMinute);
        return buildResult();
    }

    /** Combined manual-sub + snapshot in a single critical section. */
    public synchronized LiveMatchData applyUserSubAndSnapshot(long playerOutId, long playerInId) {
        applyUserSub(playerOutId, playerInId);
        return buildResult();
    }

    /** Synchronized snapshot of current state (safe to call from any thread). */
    public synchronized LiveMatchData snapshot() {
        return buildResult();
    }

    /** Build the LiveMatchData DTO from current state. Safe to call mid-match
     *  for interactive endpoints — possession uses elapsed minutes as the
     *  denominator so partial-match values stay sensible. Also populates
     *  the live-state fields (pitch/bench/subsRemaining/currentMinute). */
    LiveMatchData buildResult() {
        int denom = Math.max(1, Math.min(currentMinute, 90));
        int possessionPct = Math.min(100, homePossessionMinutes * 100 / denom);

        LiveMatchData data = new LiveMatchData();
        data.setGoalAnimations(goalAnimations); // legacy minute-keyed map (cosmetics + flag-off goals)
        // Canonical goals go on the separate ordered boundary; the legacy map is untouched
        // so a flag-off client sees byte-identical JSON.
        if (isCanonicalPlanBound()) {
            data.setCanonicalAnimations(new ArrayList<>(canonicalAnimations));
        }
        data.setHomeTeamId(teamId1);
        data.setAwayTeamId(teamId2);
        data.setHomeTeamName(homeTeamName);
        data.setAwayTeamName(awayTeamName);
        data.setCompetitionName(competitionName);
        data.setCompetitionId(competitionId);
        data.setRound(round);
        data.setTimeline(timeline);
        data.setHomeScore(homeScore);
        data.setAwayScore(awayScore);
        data.setHomePossession(possessionPct);
        data.setAwayPossession(100 - possessionPct);
        data.setHomeShots(homeShots);
        data.setAwayShots(awayShots);
        data.setHomeShotsOnTarget(homeShotsOnTarget);
        data.setAwayShotsOnTarget(awayShotsOnTarget);
        data.setHomeCorners(homeCorners);
        data.setAwayCorners(awayCorners);
        data.setHomeFouls(homeFouls);
        data.setAwayFouls(awayFouls);
        data.setHomeYellowCards(homeYellowCards);
        data.setAwayYellowCards(awayYellowCards);
        data.setHomeRedCards(homeRedCards);
        data.setAwayRedCards(awayRedCards);
        data.setHomeOffsides(homeOffsides);
        data.setAwayOffsides(awayOffsides);
        data.setFirstHalfStoppage(firstHalfStoppage);
        data.setSecondHalfStoppage(secondHalfStoppage);
        data.setStaminaSnapshots(staminaSnapshots);

        // --- Interactive live state ---
        data.setCurrentMinute(currentMinute);
        data.setFinished(finished);
        data.setAwaitingCommit(isAwaitingCommit());
        data.setHomeSubsRemaining(Math.max(0, 3 - homeSubsUsed));
        data.setAwaySubsRemaining(Math.max(0, 3 - awaySubsUsed));
        data.setHomePitch(buildPitchView(team1Ids, true));
        data.setAwayPitch(buildPitchView(team2Ids, true));
        data.setHomeBench(buildPitchView(team1Ids, false));
        data.setAwayBench(buildPitchView(team2Ids, false));
        return data;
    }

    /** Slice of {@link #matchStates} for the live pitch/bench view. */
    private List<PlayerStaminaInfo> buildPitchView(Set<Long> teamIds, boolean onPitch) {
        List<PlayerStaminaInfo> out = new ArrayList<>();
        for (PlayerMatchState s : matchStates.values()) {
            if (!teamIds.contains(s.playerId)) continue;
            if (s.isOnPitch != onPitch) continue;
            PlayerStaminaInfo info = new PlayerStaminaInfo();
            info.setPlayerId(s.playerId);
            info.setName(s.name);
            info.setPosition(s.position);
            info.setStamina((int) Math.round(s.currentStamina));
            info.setMinutesPlayed(s.minutesPlayed);
            info.setOnPitch(s.isOnPitch);
            info.setYellowCardMinute(s.yellowCardMinute);
            info.setRedCardMinute(s.redCardMinute);
            out.add(info);
        }
        return out;
    }
}
