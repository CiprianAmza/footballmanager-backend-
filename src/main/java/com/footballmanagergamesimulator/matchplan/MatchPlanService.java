package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        // Idempotent replay: a COMPLETED plan is reused as-is (0-0 => empty list).
        MatchPlan existing = matchPlanRepository.findByFixtureKey(fixtureKey).orElse(null);
        if (existing != null && isReusable(existing.getStatus())) {
            return matchEventRepository.findByFixtureKeyOrderBySlotIndexAscEventOrderAsc(fixtureKey);
        }

        long seed = seedFor(fixtureKey, competitionId, season, round, homeTeamId, awayTeamId);
        MatchPlan plan = (existing != null) ? existing
                : planningService.plan(fixtureKey, seed, homeTeamId, awayTeamId,
                        homeScore90, awayScore90, homeScoreET, awayScoreET, homeShootout, awayShootout);

        Lineup home = lineupAdapter.build(homeTeamId, homeTactic, seed);
        Lineup away = lineupAdapter.build(awayTeamId, awayTactic, seed);

        InstantMatchExecutor.MatchContext ctx =
                new InstantMatchExecutor.MatchContext(fixtureKey, competitionId, season, round);
        List<MatchEvent> events = instantExecutor.execute(plan, home, away, ctx);

        plan.setStatus(MatchPlan.Status.COMPLETED);
        matchPlanRepository.save(plan);          // plan + resolved slots
        matchEventRepository.saveAll(events);    // same transaction → atomic with the plan
        return events;
    }

    private boolean isReusable(MatchPlan.Status status) {
        return status == MatchPlan.Status.COMPLETED || status == MatchPlan.Status.COMMITTED;
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
