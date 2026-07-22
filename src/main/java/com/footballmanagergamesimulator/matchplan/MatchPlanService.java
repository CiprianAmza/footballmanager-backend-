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
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
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
        // Lock the real fixture row before checking/creating the plan. Concurrent
        // live refreshes or retries now serialize here; the second transaction
        // observes and reuses the first transaction's terminal plan.
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
                return matchEventRepository.findByFixtureKeyOrderBySlotIndexAscEventOrderAsc(fixtureKey);
            }
            deletePlanArtifacts(existing, fixtureKey); // safe: never COMMITTED here
        }

        long seed = seedFor(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId);
        MatchPlan plan = planningService.plan(fixtureKey, seed, homeTeamId, awayTeamId,
                homeScore90, awayScore90, homeScoreET, awayScoreET, homeShootout, awayShootout);

        Lineup home = lineupAdapter.build(homeTeamId, homeTactic, seed);
        Lineup away = lineupAdapter.build(awayTeamId, awayTactic, seed);

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
