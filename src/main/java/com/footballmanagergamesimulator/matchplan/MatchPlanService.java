package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.MatchAppearanceRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Facade over the canonical MatchPlan pipeline: given a decided scoreline it
 * gets-or-creates the persisted {@link MatchPlan}, adapts both squads, executes
 * the plan through the shared {@link ContributionResolver}, persists the resolved
 * slots, and returns/persists the canonical {@link MatchEvent} timeline. This is
 * the single entry point the batch and live paths call instead of the legacy
 * {@code generateMatchEvents} + {@code getScorersForTeam} distributions.
 *
 * <p>Guarded by {@code match.engine.matchPlan.enabled}; callers check
 * {@link #isEnabled()} and keep the legacy path while the flag is off. A plan is
 * reused (never regenerated or duplicated) once it exists for a fixture.
 */
@Service
public class MatchPlanService {

    @Autowired private MatchPlanningService planningService;
    @Autowired private LineupAdapter lineupAdapter;
    @Autowired private InstantMatchExecutor instantExecutor;
    @Autowired private MatchEventRepository matchEventRepository;
    @Autowired private MatchPlanRepository matchPlanRepository;
    @Autowired private MatchAppearanceRepository matchAppearanceRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchParticipantRepository matchParticipantRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchSubstitutionRepository matchSubstitutionRepository;
    @Autowired(required = false) private com.footballmanagergamesimulator.repository.MatchAnimationRecipeRepository matchAnimationRecipeRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private com.footballmanagergamesimulator.user.UserContext userContext;
    @Autowired private MatchEngineConfig engineConfig;

    public boolean isEnabled() {
        return engineConfig.getMatchPlan().isEnabled();
    }

    /** Namespaced fixture key for a competition match row, unique across match types. */
    public static String competitionFixtureKey(long matchRowId) {
        return "CTIM:" + matchRowId;
    }

    /** Stable 64-bit per-match seed. Derived from the fixture key via FNV-1a
     *  (not {@code String.hashCode()}, which is 32-bit and collision-prone) and
     *  mixed with the numeric ids. */
    public static long seedFor(String fixtureKey, long competitionId, int season, int round,
                               long homeTeamId, long awayTeamId) {
        long h = fnv1a64(fixtureKey);
        h = 31 * h + competitionId;
        h = 31 * h + season;
        h = 31 * h + round;
        h = 31 * h + homeTeamId;
        h = 31 * h + awayTeamId;
        return h;
    }

    /** 64-bit FNV-1a hash of a string. Stable across JVMs. */
    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                h ^= s.charAt(i);
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    /**
     * Idempotently build and persist the canonical goal/assist events for a
     * finished match. The plan (with resolved slots) and its events are saved in
     * ONE transaction, so a crash never leaves a completed plan without events.
     *
     * <p>Reuse is decided by the plan's {@code COMPLETED} status, NOT by event
     * existence — a 0-0 match completes with zero events and must not re-run on
     * retry. Events are returned in canonical timeline order (slot index, then
     * event type), so a reload matches the first execution's ordering.
     *
     * <p>Scores are the already-decided regular-time result; pass {@code -1} for
     * the ET / shootout pairs when not played.
     */
    @Transactional
    public List<MatchEvent> buildAndPersist(String fixtureKey, long competitionId, int season, int round,
                                            long homeTeamId, long awayTeamId,
                                            String homeTactic, String awayTactic,
                                            int homeScore90, int awayScore90,
                                            int homeScoreET, int awayScoreET,
                                            int homeShootout, int awayShootout) {
        PlanStep step = getOrCreatePlan(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId,
                homeScore90, awayScore90, homeScoreET, awayScoreET, homeShootout, awayShootout);
        if (step.reusedEvents() != null) {
            return step.reusedEvents();
        }

        // INSTANT / AI path: the squads come from the adapter (auto XI + deterministic
        // AI subs, or a user team's saved XI). Mode is decided from the authoritative
        // user-control signal, not from a PersonalizedTactic existing (admin-edited AI
        // teams may have one).
        long seed = step.plan().getSeed();
        LineupAdapter.Mode homeMode = userContext.isHumanTeam(homeTeamId)
                ? LineupAdapter.Mode.USER_SAVED : LineupAdapter.Mode.AI_INSTANT;
        LineupAdapter.Mode awayMode = userContext.isHumanTeam(awayTeamId)
                ? LineupAdapter.Mode.USER_SAVED : LineupAdapter.Mode.AI_INSTANT;
        Lineup home = lineupAdapter.build(homeTeamId, homeTactic, seed, homeMode).lineup();
        Lineup away = lineupAdapter.build(awayTeamId, awayTactic, seed, awayMode).lineup();

        return resolveAndPersist(step.plan(), home, away, fixtureKey, competitionId, season, round,
                homeTeamId, awayTeamId);
    }

    /**
     * LIVE path entry point: same canonical plan (identical goal side/minute/phase/type
     * and score chronology as the instant path — the schedule is a pure function of the
     * seed + scoreline, not of the squads), but scorers/assists are resolved from the
     * squads actually on the pitch, i.e. the caller's real live lineups carrying the
     * user's own substitutions. A player subbed off before a canonical goal minute can
     * no longer be its scorer; a substitute brought on can. Idempotent and reuse-safe
     * exactly like {@link #buildAndPersist}: a refresh/retry reuses the terminal plan.
     */
    @Transactional
    public List<MatchEvent> buildAndPersistLive(String fixtureKey, long competitionId, int season, int round,
                                                long homeTeamId, long awayTeamId,
                                                Lineup homeLive, Lineup awayLive,
                                                int homeScore90, int awayScore90,
                                                int homeScoreET, int awayScoreET,
                                                int homeShootout, int awayShootout) {
        PlanStep step = getOrCreatePlan(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId,
                homeScore90, awayScore90, homeScoreET, awayScoreET, homeShootout, awayShootout);
        if (step.reusedEvents() != null) {
            return step.reusedEvents();
        }
        return resolveAndPersist(step.plan(), homeLive, awayLive, fixtureKey, competitionId, season, round,
                homeTeamId, awayTeamId);
    }

    /** Convenience for a single-leg LIVE match with no extra time. */
    @Transactional
    public List<MatchEvent> buildAndPersistLive(String fixtureKey, long competitionId, int season, int round,
                                                long homeTeamId, long awayTeamId,
                                                Lineup homeLive, Lineup awayLive,
                                                int homeScore90, int awayScore90) {
        return buildAndPersistLive(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId,
                homeLive, awayLive, homeScore90, awayScore90, -1, -1, -1, -1);
    }

    // ==================== LIVE sub-slice 1: prepare + recover ====================

    /**
     * Persist the canonical plan for a LIVE match <em>before kickoff</em>, so a
     * browser refresh or a server restart mid-playback can reload it. Unlike
     * {@link #buildAndPersist}/{@link #buildAndPersistLive} this does NOT resolve
     * any scorer or emit any event — it saves a {@code PLANNED} plan with its
     * <em>unresolved</em> goal slots (the fixed side/minute/phase/type schedule)
     * plus the kickoff participant snapshot (starters + bench per team). Scorers
     * are resolved slot-by-slot during playback (sub-slice 2), so at kickoff no
     * player is preselected and the plan is not completed.
     *
     * <p>Idempotent recovery: an existing plan on the current algorithm version is
     * reused as-is — {@code PLANNED}/{@code IN_PROGRESS} (playback resuming, its
     * already-resolved slots recovered), {@code COMPLETED}/{@code COMMITTED} (a
     * finished match). Only a stale, non-committed plan is regenerated.
     */
    @Transactional
    public MatchPlan prepareLivePlan(String fixtureKey, long competitionId, int season, int round,
                                     long homeTeamId, long awayTeamId,
                                     Lineup homeKickoff, Lineup awayKickoff,
                                     int homeScore90, int awayScore90,
                                     int homeScoreET, int awayScoreET,
                                     int homeShootout, int awayShootout) {
        lockCompetitionFixture(fixtureKey);

        MatchPlan existing = matchPlanRepository.findByFixtureKey(fixtureKey).orElse(null);
        if (existing != null) {
            boolean reuse = existing.getStatus() == MatchPlan.Status.COMMITTED
                    || (existing.getStatus() != MatchPlan.Status.COMMITTED && isCurrentVersion(existing));
            if (reuse) {
                return existing; // recover the in-progress / terminal plan and its resolved slots
            }
            deletePlanArtifacts(existing, fixtureKey); // stale, non-committed → regenerate
        }

        long seed = seedFor(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId);
        MatchPlan plan = planningService.plan(fixtureKey, seed, homeTeamId, awayTeamId,
                homeScore90, awayScore90, homeScoreET, awayScoreET, homeShootout, awayShootout);
        plan.setStatus(MatchPlan.Status.PLANNED); // unresolved slots, no events, not completed

        int duration = plan.hadExtraTime() ? 120 : 90;
        MatchTimelineValidator.validate(homeKickoff, duration);
        MatchTimelineValidator.validate(awayKickoff, duration);

        matchPlanRepository.save(plan); // plan + unresolved goal slots
        persistKickoffParticipants(plan, homeKickoff, homeTeamId);
        persistKickoffParticipants(plan, awayKickoff, awayTeamId);
        return plan;
    }

    /** Convenience for a single-leg LIVE match with no extra time. */
    @Transactional
    public MatchPlan prepareLivePlan(String fixtureKey, long competitionId, int season, int round,
                                     long homeTeamId, long awayTeamId,
                                     Lineup homeKickoff, Lineup awayKickoff,
                                     int homeScore90, int awayScore90) {
        return prepareLivePlan(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId,
                homeKickoff, awayKickoff, homeScore90, awayScore90, -1, -1, -1, -1);
    }

    /**
     * Resolve exactly ONE due goal slot at its canonical minute, against the players
     * actually on the pitch now, and persist its goal (+ optional assist) event. The
     * live narration displays and the {@code Scorer} leaderboard records the SAME
     * contributor. Idempotent: an already-resolved slot is never re-resolved or
     * reassigned — its persisted events are returned as-is (safe replay after a
     * refresh). Moves the plan to {@code IN_PROGRESS} on the first resolution.
     */
    @Transactional
    public List<MatchEvent> resolveDueSlot(String fixtureKey, long competitionId, int season, int round,
                                           int slotIndex, List<Contributor> onPitch) {
        // Lock the fixture row so two concurrent /advance requests cannot resolve the same
        // slot twice; the loser observes the winner's resolved slot and returns it.
        lockCompetitionFixture(fixtureKey);
        MatchPlan plan = matchPlanRepository.findByFixtureKey(fixtureKey)
                .orElseThrow(() -> new IllegalStateException("No live plan for " + fixtureKey));
        GoalSlot slot = plan.getGoalSlots().stream()
                .filter(s -> s.getSlotIndex() == slotIndex).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No slot " + slotIndex + " in " + fixtureKey));

        if (slot.isResolved()) {
            // Idempotent replay: never reassign; return the already-persisted events.
            return matchEventRepository.findByFixtureKey(fixtureKey).stream()
                    .filter(e -> e.getSlotIndex() == slotIndex)
                    .sorted(java.util.Comparator.comparingInt(MatchEvent::getEventOrder))
                    .collect(java.util.stream.Collectors.toList());
        }
        // Terminal plans are immutable: no new resolution once finished/committed.
        if (isTerminal(plan)) {
            throw new IllegalStateException("Cannot resolve a slot on a terminal plan: " + fixtureKey);
        }

        InstantMatchExecutor.MatchContext ctx =
                new InstantMatchExecutor.MatchContext(fixtureKey, competitionId, season, round);
        List<MatchEvent> events = instantExecutor.resolveSlot(plan, slot, onPitch, ctx);
        if (plan.getStatus() == MatchPlan.Status.PLANNED) {
            plan.setStatus(MatchPlan.Status.IN_PROGRESS);
        }
        matchPlanRepository.save(plan);       // persist the now-resolved slot
        matchEventRepository.saveAll(events); // atomic with the slot resolution
        return events;
    }

    /**
     * Record one real substitution as it happens: the next consecutive per-team
     * {@code subIndex}, its minute, and who went off/on. The incoming player's
     * fielded position is reconstructed on recovery from the kickoff snapshot + this
     * timeline (via {@link LiveLineupFactory#rebuild}), so it is not stored redundantly.
     */
    @Transactional
    public void recordLiveSubstitution(String fixtureKey, long teamId, int minute, long offId, long onId) {
        // Lock so the per-team subIndex is allocated serially — two concurrent /substitute
        // requests cannot claim the same index.
        lockCompetitionFixture(fixtureKey);
        MatchPlan plan = matchPlanRepository.findByFixtureKey(fixtureKey)
                .orElseThrow(() -> new IllegalStateException("No live plan for " + fixtureKey));
        if (isTerminal(plan)) {
            throw new IllegalStateException("Cannot record a substitution on a terminal plan: " + fixtureKey);
        }
        int nextIndex = (int) matchSubstitutionRepository
                .findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan).stream()
                .filter(s -> s.getTeamId() == teamId).count();
        matchSubstitutionRepository.save(new MatchSubstitution(plan, teamId, nextIndex, minute, offId, onId));
    }

    private boolean isTerminal(MatchPlan plan) {
        return plan.getStatus() == MatchPlan.Status.COMPLETED
                || plan.getStatus() == MatchPlan.Status.COMMITTED;
    }

    /** Acquire the pessimistic fixture lock (used by the live commit to serialize the whole
     *  finalization, so two concurrent /commit requests cannot both run the side effects). */
    public void lockFixture(String fixtureKey) {
        lockCompetitionFixture(fixtureKey);
    }

    /** Durable idempotency check: is this fixture's plan already COMMITTED? A committed plan
     *  means the commit already succeeded and durably persisted — a retry must not re-run. */
    public boolean isPlanCommitted(String fixtureKey) {
        return matchPlanRepository.findByFixtureKey(fixtureKey)
                .map(p -> p.getStatus() == MatchPlan.Status.COMMITTED).orElse(false);
    }

    /**
     * Finalize a live plan after full time: {@code COMPLETED}, persisting the derived
     * appearance projection from the kickoff snapshot + the recorded substitutions.
     * It NEVER regenerates the fixed schedule or re-resolves any slot (that is the
     * bug the instant finalizer would introduce on a prepared plan). Idempotent, and
     * a no-op on a {@code COMMITTED} (immutable) or already-{@code COMPLETED} plan.
     */
    @Transactional
    public void finishLivePlan(String fixtureKey) {
        lockCompetitionFixture(fixtureKey);
        MatchPlan plan = matchPlanRepository.findByFixtureKey(fixtureKey).orElse(null);
        if (plan == null) return;
        if (plan.getStatus() == MatchPlan.Status.COMMITTED || plan.getStatus() == MatchPlan.Status.COMPLETED) {
            return;
        }
        // Terminal invariants: every scoring slot must be resolved, and the persisted goal
        // events must total exactly the planned score. A live match cannot be finalized with
        // a slot the user never saw resolved, or with a goal count that disagrees with the plan.
        long unresolved = plan.getGoalSlots().stream().filter(s -> !s.isResolved()).count();
        if (unresolved > 0) {
            throw new IllegalStateException("Cannot finish " + fixtureKey + ": "
                    + unresolved + " unresolved goal slot(s)");
        }
        int plannedGoals = plan.getGoalSlots().size();
        long goalEvents = matchEventRepository.findByFixtureKey(fixtureKey).stream()
                .filter(e -> "goal".equals(e.getEventType())).count();
        if (goalEvents != plannedGoals) {
            throw new IllegalStateException("Cannot finish " + fixtureKey + ": " + goalEvents
                    + " goal events != " + plannedGoals + " planned goals");
        }

        int duration = plan.hadExtraTime() ? 120 : 90;
        persistLiveAppearances(plan, plan.getHomeTeamId(), duration);
        persistLiveAppearances(plan, plan.getAwayTeamId(), duration);
        plan.setStatus(MatchPlan.Status.COMPLETED);
        matchPlanRepository.save(plan);
    }

    /**
     * Finalize a LIVE knockout that went to extra time by APPENDING the new extra-time goal
     * slots to the SAME (locked) in-progress plan — never deleting or regenerating the
     * regular-time slots/events the user already watched. The ET slots get fresh slot
     * indices continuing from the existing ones (their minutes 91-120 sort after regular
     * time), are resolved against the end-of-90' on-pitch set (reconstructed from the
     * kickoff snapshot + recorded subs), and their goal/assist events are persisted. The
     * shootout is carried on the plan but produces no goal slots. Then COMPLETED (with
     * appearances for a 120' match). Idempotent: on a plan that already has extra time, or a
     * terminal plan, it only finalizes / no-ops.
     */
    @Transactional
    public void appendExtraTimeAndFinalize(String fixtureKey, long competitionId, int season, int round,
                                           long homeTeamId, long awayTeamId,
                                           int homeScoreET, int awayScoreET,
                                           int homeShootout, int awayShootout) {
        lockCompetitionFixture(fixtureKey);
        MatchPlan plan = matchPlanRepository.findByFixtureKey(fixtureKey).orElse(null);
        if (plan == null || plan.getStatus() == MatchPlan.Status.COMMITTED
                || plan.getStatus() == MatchPlan.Status.COMPLETED) {
            return; // immutable / already finalized
        }

        if (!plan.hadExtraTime()) {
            plan.recordExtraTime(homeScoreET, awayScoreET, homeShootout, awayShootout);
            List<GoalSlot> etSlots = planningService.extraTimeSlots(
                    plan.getSeed(), homeTeamId, awayTeamId, homeScoreET, awayScoreET);
            if (!etSlots.isEmpty()) {
                LivePlanSnapshot snap = loadLivePlanSnapshot(fixtureKey).orElseThrow();
                Lineup homeLineup = snap.rebuildLineup(homeTeamId);
                Lineup awayLineup = snap.rebuildLineup(awayTeamId);
                InstantMatchExecutor.MatchContext ctx =
                        new InstantMatchExecutor.MatchContext(fixtureKey, competitionId, season, round);
                int nextIndex = plan.getGoalSlots().size();
                List<MatchEvent> etEvents = new ArrayList<>();
                for (GoalSlot slot : etSlots) {
                    slot.setSlotIndex(nextIndex++);
                    plan.addGoalSlot(slot); // append; regular-time slots untouched
                    boolean isHome = slot.getTeamId() == plan.getHomeTeamId();
                    Lineup lineup = isHome ? homeLineup : awayLineup;
                    etEvents.addAll(instantExecutor.resolveSlot(plan, slot,
                            lineup.onPitchAt(slot.getMinute()), ctx));
                }
                matchEventRepository.saveAll(etEvents);
            }
        }

        // Finalize to COMPLETED with 120' appearances (derived from the actual timeline).
        persistLiveAppearances(plan, plan.getHomeTeamId(), 120);
        persistLiveAppearances(plan, plan.getAwayTeamId(), 120);
        plan.setStatus(MatchPlan.Status.COMPLETED);
        matchPlanRepository.save(plan);
    }

    /**
     * Detached recovery view (browser refresh / restart mid-playback). Built entirely
     * inside this transaction — every lazy collection is copied into plain records —
     * so it is safe to read after the transaction closes (no
     * {@code LazyInitializationException}). Replaces exposing the JPA entity directly.
     */
    @Transactional
    public java.util.Optional<LivePlanSnapshot> loadLivePlanSnapshot(String fixtureKey) {
        MatchPlan plan = matchPlanRepository.findByFixtureKey(fixtureKey).orElse(null);
        if (plan == null) return java.util.Optional.empty();

        List<LivePlanSnapshot.SlotView> slots = new ArrayList<>();
        for (GoalSlot s : plan.getGoalSlots()) {
            slots.add(new LivePlanSnapshot.SlotView(s.getSlotIndex(), s.getTeamId(), s.getMinute(),
                    s.getPhase(), s.getGoalType(), s.isResolved(), s.getScorerId(), s.getAssistId()));
        }
        List<LivePlanSnapshot.ParticipantView> participants = new ArrayList<>();
        for (MatchParticipant p : matchParticipantRepository
                .findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(plan)) {
            participants.add(new LivePlanSnapshot.ParticipantView(p.getTeamId(), p.getParticipantIndex(),
                    p.isStarter(), p.toContributor()));
        }
        List<LivePlanSnapshot.SubView> subs = new ArrayList<>();
        for (MatchSubstitution s : matchSubstitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan)) {
            subs.add(new LivePlanSnapshot.SubView(s.getTeamId(), s.getSubIndex(), s.getMinute(),
                    s.getOffPlayerId(), s.getOnPlayerId()));
        }
        int duration = plan.hadExtraTime() ? 120 : 90;
        return java.util.Optional.of(new LivePlanSnapshot(fixtureKey, plan.getSeed(),
                plan.getHomeTeamId(), plan.getAwayTeamId(), plan.getStatus(), duration,
                plan.getHomeShootout(), plan.getAwayShootout(),
                slots, participants, subs));
    }

    /** Persist only the kickoff squad snapshot (starters + bench). Substitutions and
     *  the derived appearance projection are recorded later, as they actually occur. */
    private void persistKickoffParticipants(MatchPlan plan, Lineup lineup, long teamId) {
        List<MatchParticipant> participants = new ArrayList<>();
        int index = 0;
        for (Contributor c : lineup.getStartingXI()) {
            participants.add(MatchParticipant.of(plan, teamId, index++, true, c));
        }
        for (Contributor c : lineup.getBench()) {
            participants.add(MatchParticipant.of(plan, teamId, index++, false, c));
        }
        matchParticipantRepository.saveAll(participants);
    }

    /** Derive and persist the appearance projection for one team from its kickoff
     *  snapshot + recorded substitutions (the actual timeline). */
    private void persistLiveAppearances(MatchPlan plan, long teamId, int duration) {
        List<Contributor> starters = new ArrayList<>();
        List<Contributor> bench = new ArrayList<>();
        for (MatchParticipant p : matchParticipantRepository
                .findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(plan)) {
            if (p.getTeamId() != teamId) continue;
            (p.isStarter() ? starters : bench).add(p.toContributor());
        }
        List<LiveLineupFactory.SubRecord> subs = new ArrayList<>();
        for (MatchSubstitution s : matchSubstitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan)) {
            if (s.getTeamId() != teamId) continue;
            subs.add(new LiveLineupFactory.SubRecord(s.getSubIndex(), s.getMinute(),
                    s.getOffPlayerId(), s.getOnPlayerId()));
        }
        Lineup lineup = LiveLineupFactory.rebuild(starters, bench, subs);
        List<MatchAppearance> appearances = new ArrayList<>();
        for (Lineup.Appearance a : lineup.appearances()) {
            appearances.add(new MatchAppearance(plan, teamId, a.playerId(),
                    a.startMinute(), a.exitMinute(), a.minutesPlayed(duration)));
        }
        matchAppearanceRepository.saveAll(appearances);
    }

    /** The authoritative kickoff lineups for a fixture. */
    public record KickoffLineups(Lineup home, Lineup away) {}

    /**
     * Build the authoritative kickoff XI+bench for BOTH the instant and live paths through
     * the same {@link LineupAdapter}: a human team's saved {@code first11} (USER_SAVED),
     * an AI team's automatic eleven (AI_INSTANT), including designated penalty/free-kick
     * takers. The live session adopts this XI so a watched match fields exactly the saved
     * team the instant execution would — no divergent "highest-rated eleven" selection.
     */
    public KickoffLineups buildKickoffLineups(String fixtureKey, long competitionId, int season, int round,
                                              long homeTeamId, long awayTeamId,
                                              String homeTactic, String awayTactic) {
        long seed = seedFor(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId);
        LineupAdapter.Mode homeMode = userContext.isHumanTeam(homeTeamId)
                ? LineupAdapter.Mode.USER_SAVED : LineupAdapter.Mode.AI_INSTANT;
        LineupAdapter.Mode awayMode = userContext.isHumanTeam(awayTeamId)
                ? LineupAdapter.Mode.USER_SAVED : LineupAdapter.Mode.AI_INSTANT;
        Lineup home = lineupAdapter.build(homeTeamId, homeTactic, seed, homeMode).lineup();
        Lineup away = lineupAdapter.build(awayTeamId, awayTactic, seed, awayMode).lineup();
        return new KickoffLineups(home, away);
    }

    /** Reuse decision + plan creation, shared by the instant and live paths. When the
     *  returned step carries {@code reusedEvents}, the caller must return them verbatim
     *  (a terminal plan already exists); otherwise it carries a fresh, unsaved plan to
     *  resolve. Locks the real fixture row first so concurrent refreshes/retries
     *  serialize and the second transaction observes the first's terminal plan. */
    private PlanStep getOrCreatePlan(String fixtureKey, long competitionId, int season, int round,
                                     long homeTeamId, long awayTeamId,
                                     int homeScore90, int awayScore90,
                                     int homeScoreET, int awayScoreET,
                                     int homeShootout, int awayShootout) {
        lockCompetitionFixture(fixtureKey);

        // Reuse / regenerate policy:
        //  - COMMITTED is immutable — always reuse, never regenerate, even on a
        //    version mismatch (a finalized match must not be rewritten with today's
        //    squads/attributes). A stale COMMITTED plan is migrated explicitly, not here.
        //  - COMPLETED on the current version is reused as-is.
        //  - PLANNED, or COMPLETED on a stale version (still pre-result-commit), may be
        //    regenerated.
        MatchPlan existing = matchPlanRepository.findByFixtureKey(fixtureKey).orElse(null);
        if (existing != null) {
            boolean reuse = existing.getStatus() == MatchPlan.Status.COMMITTED
                    || (existing.getStatus() == MatchPlan.Status.COMPLETED && isCurrentVersion(existing));
            if (reuse) {
                return new PlanStep(null,
                        matchEventRepository.findByFixtureKeyOrderBySlotIndexAscEventOrderAsc(fixtureKey));
            }
            deletePlanArtifacts(existing, fixtureKey); // safe: never COMMITTED here
        }

        long seed = seedFor(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId);
        MatchPlan plan = planningService.plan(fixtureKey, seed, homeTeamId, awayTeamId,
                homeScore90, awayScore90, homeScoreET, awayScoreET, homeShootout, awayShootout);
        return new PlanStep(plan, null);
    }

    /** Resolve the plan's slots against the two lineups (the ONLY place the shared
     *  {@link ContributionResolver} runs for a match) and persist plan + resolved slots
     *  + events + squad/substitution/appearance timeline in one transaction. */
    private List<MatchEvent> resolveAndPersist(MatchPlan plan, Lineup home, Lineup away,
                                               String fixtureKey, long competitionId, int season, int round,
                                               long homeTeamId, long awayTeamId) {
        int duration = plan.hadExtraTime() ? 120 : 90;
        MatchTimelineValidator.validate(home, duration);
        MatchTimelineValidator.validate(away, duration);

        InstantMatchExecutor.MatchContext ctx =
                new InstantMatchExecutor.MatchContext(fixtureKey, competitionId, season, round);
        List<MatchEvent> events = instantExecutor.execute(plan, home, away, ctx);

        plan.setStatus(MatchPlan.Status.COMPLETED);
        matchPlanRepository.save(plan);          // plan + resolved slots
        matchEventRepository.saveAll(events);    // same transaction → atomic with the plan
        persistTimeline(plan, home, homeTeamId, duration);
        persistTimeline(plan, away, awayTeamId, duration);
        return events;
    }

    /** Either a reuse hit ({@code reusedEvents != null}) or a fresh plan to resolve
     *  ({@code plan != null}); exactly one is non-null. */
    private record PlanStep(MatchPlan plan, List<MatchEvent> reusedEvents) {}

    /** Persist the canonical squad + substitutions, and the derived appearance projection. */
    private void persistTimeline(MatchPlan plan, Lineup lineup, long teamId, int duration) {
        List<MatchParticipant> participants = new ArrayList<>();
        int index = 0;
        for (Contributor c : lineup.getStartingXI()) {
            participants.add(MatchParticipant.of(plan, teamId, index++, true, c));
        }
        for (Contributor c : lineup.getBench()) {
            participants.add(MatchParticipant.of(plan, teamId, index++, false, c));
        }
        matchParticipantRepository.saveAll(participants);

        List<MatchSubstitution> subs = new ArrayList<>();
        for (Lineup.SubMove s : lineup.getSubs()) {
            // subIndex comes from the SubMove's own sequence, not the persist order.
            subs.add(new MatchSubstitution(plan, teamId, s.sequence(), s.minute(),
                    s.offPlayerId(), s.on().playerId()));
        }
        matchSubstitutionRepository.saveAll(subs);

        // Derived projection.
        List<MatchAppearance> appearances = new ArrayList<>();
        for (Lineup.Appearance a : lineup.appearances()) {
            appearances.add(new MatchAppearance(plan, teamId, a.playerId(),
                    a.startMinute(), a.exitMinute(), a.minutesPlayed(duration)));
        }
        matchAppearanceRepository.saveAll(appearances);
    }

    /** Remove a stale/leftover plan and every artifact that references it, then flush
     *  so the fresh plan can reuse the unique fixture key. */
    private void deletePlanArtifacts(MatchPlan plan, String fixtureKey) {
        if (matchAnimationRecipeRepository != null) {
            matchAnimationRecipeRepository.deleteByFixtureKey(fixtureKey);
        }
        matchEventRepository.findByFixtureKey(fixtureKey).forEach(matchEventRepository::delete);
        matchAppearanceRepository.findByMatchPlan(plan).forEach(matchAppearanceRepository::delete);
        matchSubstitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan)
                .forEach(matchSubstitutionRepository::delete);
        matchParticipantRepository.findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(plan).forEach(matchParticipantRepository::delete);
        matchPlanRepository.delete(plan); // cascades goal slots
        matchPlanRepository.flush();
    }

    /**
     * Finalize the plan: COMPLETED → COMMITTED. Call this in the SAME transaction
     * that persists the match result, {@code Scorer} and statistics, so a
     * committed match is exactly one whose result is durably recorded — and from
     * then on immutable. A no-op if the plan is missing or already committed.
     */
    @Transactional
    public void markCommitted(String fixtureKey) {
        matchPlanRepository.findByFixtureKey(fixtureKey).ifPresent(plan -> {
            if (plan.getStatus() == MatchPlan.Status.COMPLETED) {
                plan.setStatus(MatchPlan.Status.COMMITTED);
                matchPlanRepository.save(plan);
            }
        });
    }

    private boolean isCurrentVersion(MatchPlan plan) {
        return MatchPlanningService.ALGORITHM_VERSION.equals(plan.getAlgorithmVersion());
    }

    private void lockCompetitionFixture(String fixtureKey) {
        if (fixtureKey == null || !fixtureKey.startsWith("CTIM:")) {
            throw new IllegalArgumentException("Unsupported canonical fixture key: " + fixtureKey);
        }
        final long fixtureId;
        try {
            fixtureId = Long.parseLong(fixtureKey.substring("CTIM:".length()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid competition fixture key: " + fixtureKey, ex);
        }
        fixtureRepository.findByIdForUpdate(fixtureId)
                .orElseThrow(() -> new IllegalStateException("Fixture does not exist: " + fixtureKey));
    }

    /** Convenience for league / single-leg matches with no extra time. */
    @Transactional
    public List<MatchEvent> buildAndPersist(String fixtureKey, long competitionId, int season, int round,
                                            long homeTeamId, long awayTeamId,
                                            String homeTactic, String awayTactic,
                                            int homeScore90, int awayScore90) {
        return buildAndPersist(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId,
                homeTactic, awayTactic, homeScore90, awayScore90, -1, -1, -1, -1);
    }
}
