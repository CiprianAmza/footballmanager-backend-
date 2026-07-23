package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.matchplan.MatchAppearance;
import com.footballmanagergamesimulator.matchplan.MatchEventProjection;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchAppearanceRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.MatchPlayerRatingRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flag-ON proof that the AI-vs-AI fast-path is served by the ONE canonical engine
 * (MatchPlan + GoalSlot + InstantMatchExecutor + ContributionResolver + MatchEvent),
 * driven through the real production round entry point on the full bootstrap world.
 *
 * <p>Asserts, for every AI fixture of a league round: a committed MatchPlan exists; the
 * goal-event count equals the persisted match score (90' + extra time, never the
 * shootout); the projected Scorer goals/assists equal the MatchEvent tally exactly; and
 * every scorer/assister was actually on the pitch at the goal minute.
 *
 * <p>Idempotency is proven through the REAL entry point: re-running the same round via
 * {@code simulateRound} is a clean no-op (no exception, and no change to any effect —
 * fixture score, plan/events, Scorer, MatchStats, result details, standings, player stats,
 * ratings). A concurrent same-round race proves a single commit with the loser no-opping.
 *
 * <p>The datasource pins a high H2 {@code LOCK_TIMEOUT} so the losing thread of the
 * concurrency race waits for the winner's fixture lock and then no-ops, rather than timing
 * out — the design serializes on the fixture row, it does not fail the loser.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.match-plan.enabled=true",
        "gameplay.player-availability-disabled=false",
        "spring.datasource.url=jdbc:h2:mem:sentinelfastpath;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Canonical AI fast-path — flag ON")
class CanonicalAiFastPathIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private MatchPlanRepository matchPlanRepository;
    @Autowired private MatchEventRepository matchEventRepository;
    @Autowired private MatchAppearanceRepository matchAppearanceRepository;
    @Autowired private ScorerRepository scorerRepository;
    @Autowired private MatchStatsRepository matchStatsRepository;
    @Autowired private CompetitionTeamInfoDetailRepository detailRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompDetailRepository;
    @Autowired private MatchPlayerRatingRepository matchPlayerRatingRepository;
    @Autowired private PlayerSeasonStatRepository playerSeasonStatRepository;
    @Autowired private MatchEngineConfig engineConfig;

    private Competition firstLeague() {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1)
                .min((a, b) -> Long.compare(a.getId(), b.getId()))
                .orElseThrow(() -> new IllegalStateException("no league competition in bootstrap world"));
    }

    @Test
    @Order(10)
    @DisplayName("AI league round: canonical plan, events==score, Scorer==tally, contributors on pitch")
    void aiLeagueRound_isFullyCanonical() {
        Competition league = firstLeague();
        int season = 1;
        int round = 1;
        List<CompetitionTeamInfoMatch> fixtures = matchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(league.getId(), (long) round, "1");
        assertFalse(fixtures.isEmpty(), "precondition: round-1 fixtures exist");

        competitionController.simulateRound(String.valueOf(league.getId()), String.valueOf(round));

        int planCount = 0;
        for (CompetitionTeamInfoMatch fixtureRow : fixtures) {
            CompetitionTeamInfoMatch fixture = matchRepository.findById(fixtureRow.getId()).orElseThrow();
            String key = MatchPlanService.competitionFixtureKey(fixture.getId());

            MatchPlan plan = matchPlanRepository.findByFixtureKey(key).orElse(null);
            assertNotNull(plan, "canonical MatchPlan created for fixture " + key);
            assertEquals(MatchPlan.Status.COMMITTED, plan.getStatus(),
                    "plan committed once all match effects are persisted");
            planCount++;

            int homeScore = fixture.getTeam1Score();
            int awayScore = fixture.getTeam2Score();
            assertTrue(homeScore >= 0 && awayScore >= 0, "fixture score persisted");
            // League has no shootout; the plan must reflect that (no phantom shootout goals).
            assertEquals(-1, plan.getHomeShootout());
            assertEquals(-1, plan.getAwayShootout());

            List<MatchEvent> events = matchEventRepository.findByFixtureKey(key);
            long goalEvents = events.stream().filter(e -> "goal".equals(e.getEventType())).count();
            assertEquals(homeScore + awayScore, goalEvents,
                    "goal-event count equals the persisted score for fixture " + key);

            long homeTeamId = fixture.getTeam1Id();
            long awayTeamId = fixture.getTeam2Id();

            // --- Scorer projection equals the canonical MatchEvent tally, per player. ---
            Map<Long, MatchEventProjection.Tally> tally = MatchEventProjection.aggregate(events);
            Map<Long, int[]> scorerByPlayer = new HashMap<>();
            for (long teamId : new long[]{homeTeamId, awayTeamId}) {
                for (Scorer s : scorerRepository
                        .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId(
                                league.getId(), season, round, teamId)) {
                    int[] ga = scorerByPlayer.computeIfAbsent(s.getPlayerId(), k -> new int[2]);
                    ga[0] += s.getGoals();
                    ga[1] += s.getAssists();
                }
            }
            for (Map.Entry<Long, MatchEventProjection.Tally> e : tally.entrySet()) {
                int[] ga = scorerByPlayer.get(e.getKey());
                assertNotNull(ga, "player " + e.getKey() + " credited by events must have a Scorer row");
                assertEquals(e.getValue().goals, ga[0], "goals for player " + e.getKey());
                assertEquals(e.getValue().assists, ga[1], "assists for player " + e.getKey());
            }
            // No Scorer goal/assist that the canonical events did not credit.
            long scorerGoals = scorerByPlayer.values().stream().mapToLong(a -> a[0]).sum();
            long scorerAssists = scorerByPlayer.values().stream().mapToLong(a -> a[1]).sum();
            long eventAssists = events.stream().filter(e -> "assist".equals(e.getEventType())).count();
            assertEquals(goalEvents, scorerGoals, "total Scorer goals == goal events");
            assertEquals(eventAssists, scorerAssists, "total Scorer assists == assist events");

            // --- Every contributor was actually on the pitch at the goal minute. ---
            Map<Long, List<MatchAppearance>> appearancesByTeam = new HashMap<>();
            for (MatchAppearance a : matchAppearanceRepository.findByMatchPlan(plan)) {
                appearancesByTeam.computeIfAbsent(a.getTeamId(), k -> new ArrayList<>()).add(a);
            }
            for (MatchEvent e : events) {
                if (!"goal".equals(e.getEventType()) && !"assist".equals(e.getEventType())) continue;
                List<MatchAppearance> teamApps = appearancesByTeam.getOrDefault(e.getTeamId(), List.of());
                boolean onPitch = teamApps.stream()
                        .anyMatch(a -> a.getPlayerId() == e.getPlayerId() && a.onPitchAt(e.getMinute()));
                assertTrue(onPitch, e.getEventType() + " by " + e.getPlayerId()
                        + " at min " + e.getMinute() + " who was not on the pitch (fixture " + key + ")");
            }
        }
        assertEquals(fixtures.size(), planCount, "one canonical plan per AI fixture");

        // -------- Idempotency through the REAL entry point: re-running the SAME round must be
        // a clean no-op — no exception, and NOTHING changes (score, plan/events, Scorer,
        // MatchStats, result details, standings, player stats, ratings). --------
        Map<String, Long> before = roundStateSnapshot(league, season, round, fixtures);
        competitionController.simulateRound(String.valueOf(league.getId()), String.valueOf(round));
        Map<String, Long> after = roundStateSnapshot(league, season, round, fixtures);
        assertEquals(before, after,
                "re-running a committed round through simulateRound must be a clean no-op");
    }

    @Test
    @Order(20)
    @DisplayName("Benchmark: legacy (flag OFF) vs canonical (flag ON) for a representative round")
    void benchmark_legacyVsCanonical() {
        Competition league = firstLeague();

        // Round 2 with the legacy simplified path (flag toggled off on the live bean).
        engineConfig.getMatchPlan().setEnabled(false);
        long t0 = System.nanoTime();
        competitionController.simulateRound(String.valueOf(league.getId()), "2");
        long legacyMs = (System.nanoTime() - t0) / 1_000_000;
        long plansAfterLegacyRound = countPlansForRound(league, 2);

        // Round 3 with the canonical fast-path.
        engineConfig.getMatchPlan().setEnabled(true);
        long t1 = System.nanoTime();
        competitionController.simulateRound(String.valueOf(league.getId()), "3");
        long canonicalMs = (System.nanoTime() - t1) / 1_000_000;
        long plansAfterCanonicalRound = countPlansForRound(league, 3);

        System.out.println("[BENCH canonical-ai-fastpath] league=" + league.getId()
                + " legacyRound2=" + legacyMs + "ms canonicalRound3=" + canonicalMs + "ms");

        assertEquals(0, plansAfterLegacyRound, "flag OFF writes no canonical plan");
        assertTrue(plansAfterCanonicalRound > 0, "flag ON writes canonical plans");
        // Correctness dominates, but a severe fast-forward regression is not acceptable.
        // Loose ceiling (guards against an accidental per-player query storm), not a micro-bench.
        assertTrue(canonicalMs <= Math.max(2000, legacyMs * 8 + 1500),
                "canonical round unexpectedly slow: legacy=" + legacyMs + "ms canonical=" + canonicalMs + "ms");
    }

    @Test
    @Order(30)
    @DisplayName("Concurrent same-round race: single commit, single set of effects, loser no-ops")
    void concurrentSameRound_singleCommitLoserNoOps() throws Exception {
        Competition league = firstLeague();
        int season = 1;
        int round = 4; // a fresh round not simulated by the earlier tests
        List<CompetitionTeamInfoMatch> fixtures = matchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(league.getId(), (long) round, "1");
        assertFalse(fixtures.isEmpty(), "precondition: round-4 fixtures exist");
        engineConfig.getMatchPlan().setEnabled(true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        Callable<Throwable> race = () -> {
            startGate.await();
            try {
                competitionController.simulateRound(String.valueOf(league.getId()), String.valueOf(round));
                return null;
            } catch (Throwable t) {
                return t;
            }
        };
        Future<Throwable> f1 = pool.submit(race);
        Future<Throwable> f2 = pool.submit(race);
        startGate.countDown();
        Throwable e1 = f1.get(120, TimeUnit.SECONDS);
        Throwable e2 = f2.get(120, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // The fixture lock serializes the two rounds; the loser observes the committed plan and
        // no-ops. Neither request may fail (no duplicate, no error), per the idempotency contract.
        assertNull(e1, "first concurrent simulateRound threw: " + e1);
        assertNull(e2, "second concurrent simulateRound threw: " + e2);

        // Single set of effects: exactly one committed plan, one detail row, and events==score.
        List<CompetitionTeamInfoDetail> details = detailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(league.getId(), round, season);
        assertEquals(fixtures.size(), details.size(),
                "each fixture produced exactly one detail row (no duplication from the race)");

        for (CompetitionTeamInfoMatch fixtureRow : fixtures) {
            CompetitionTeamInfoMatch fixture = matchRepository.findById(fixtureRow.getId()).orElseThrow();
            String key = MatchPlanService.competitionFixtureKey(fixture.getId());
            MatchPlan plan = matchPlanRepository.findByFixtureKey(key).orElse(null);
            assertNotNull(plan, "committed plan for " + key);
            assertEquals(MatchPlan.Status.COMMITTED, plan.getStatus());
            long goalEvents = matchEventRepository.findByFixtureKey(key).stream()
                    .filter(e -> "goal".equals(e.getEventType())).count();
            assertEquals(fixture.getTeam1Score() + fixture.getTeam2Score(), goalEvents,
                    "single set of goal events == score for " + key);
        }
    }

    /**
     * A comparable snapshot of every match effect a re-run could duplicate. Global counts detect
     * duplicated rows; standings sums and per-fixture scores detect double-application / divergence.
     */
    private Map<String, Long> roundStateSnapshot(Competition league, int season, int round,
                                                 List<CompetitionTeamInfoMatch> fixtures) {
        Map<String, Long> m = new TreeMap<>();
        m.put("plans", matchPlanRepository.count());
        m.put("events", matchEventRepository.count());
        m.put("matchStats", (long) matchStatsRepository
                .findAllByCompetitionIdAndSeasonNumber(league.getId(), season).size());
        m.put("details", (long) detailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(league.getId(), round, season).size());
        m.put("ratings", matchPlayerRatingRepository.count());
        m.put("seasonStats", playerSeasonStatRepository.count());

        List<Scorer> scorers = scorerRepository.findAllByCompetitionIdAndSeasonNumber(league.getId(), season);
        m.put("scorerRows", (long) scorers.size());
        m.put("scorerGoals", scorers.stream().mapToLong(Scorer::getGoals).sum());
        m.put("scorerAssists", scorers.stream().mapToLong(Scorer::getAssists).sum());

        long games = 0, points = 0, goalsFor = 0, goalsAgainst = 0;
        for (TeamCompetitionDetail t : teamCompDetailRepository.findAllByCompetitionId(league.getId())) {
            games += t.getGames();
            points += t.getPoints();
            goalsFor += t.getGoalsFor();
            goalsAgainst += t.getGoalsAgainst();
        }
        m.put("standingsGames", games);
        m.put("standingsPoints", points);
        m.put("standingsGoalsFor", goalsFor);
        m.put("standingsGoalsAgainst", goalsAgainst);

        for (CompetitionTeamInfoMatch f : fixtures) {
            CompetitionTeamInfoMatch fresh = matchRepository.findById(f.getId()).orElseThrow();
            m.put("fixtureScore:" + f.getId(),
                    fresh.getTeam1Score() * 1000L + fresh.getTeam2Score());
        }
        return m;
    }

    private long countPlansForRound(Competition league, int round) {
        Set<String> keys = new HashSet<>();
        for (CompetitionTeamInfoMatch m : matchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(league.getId(), (long) round, "1")) {
            keys.add(MatchPlanService.competitionFixtureKey(m.getId()));
        }
        return keys.stream().filter(k -> matchPlanRepository.findByFixtureKey(k).isPresent()).count();
    }
}
