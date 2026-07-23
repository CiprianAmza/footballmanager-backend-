package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.matchplan.MatchAppearance;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.MatchEventProjection;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchAppearanceRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flag-ON proof that the AI-vs-AI fast-path is served by the ONE canonical engine
 * (MatchPlan + GoalSlot + InstantMatchExecutor + ContributionResolver + MatchEvent),
 * driven through the real production round entry point on the full bootstrap world.
 *
 * <p>Asserts, for every AI fixture of a league round: a committed MatchPlan exists; the
 * goal-event count equals the persisted match score (90' + extra time, never the
 * shootout); the projected Scorer goals/assists equal the MatchEvent tally exactly; and
 * every scorer/assister was actually on the pitch at the goal minute. Also proves
 * idempotency (a re-run adds no duplicate plan/event/scorer) and reports a legacy-vs-
 * canonical timing benchmark for a representative round.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.match-plan.enabled=true",
        "gameplay.player-availability-disabled=false"
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
    @Autowired private MatchPlanService matchPlanService;
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
                appearancesByTeam.computeIfAbsent(a.getTeamId(), k -> new java.util.ArrayList<>()).add(a);
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

        // -------- Idempotency: retrying the SAME fixture reuses the committed plan and adds
        // no duplicate plan / goal slot / event. A committed plan short-circuits before any
        // resolution, so passing empty lineups is safe — nothing is re-resolved. --------
        long plansBefore = matchPlanRepository.count();
        for (CompetitionTeamInfoMatch fixtureRow : fixtures) {
            CompetitionTeamInfoMatch fixture = matchRepository.findById(fixtureRow.getId()).orElseThrow();
            String key = MatchPlanService.competitionFixtureKey(fixture.getId());
            List<MatchEvent> before = matchEventRepository.findByFixtureKey(key);
            List<MatchEvent> retried = matchPlanService.buildAndPersistLive(
                    key, league.getId(), season, round, fixture.getTeam1Id(), fixture.getTeam2Id(),
                    new Lineup(List.of(), List.of()), new Lineup(List.of(), List.of()),
                    fixture.getTeam1Score(), fixture.getTeam2Score());
            assertEquals(before.size(), retried.size(),
                    "retry of committed fixture " + key + " reuses the exact event timeline");
            assertEquals(before.size(), matchEventRepository.findByFixtureKey(key).size(),
                    "retry duplicates no canonical MatchEvent for " + key);
            assertTrue(matchPlanService.isPlanCommitted(key), "plan remains committed for " + key);
        }
        assertEquals(plansBefore, matchPlanRepository.count(),
                "committed plans are immutable — a retry creates none");
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

    private long countPlansForRound(Competition league, int round) {
        Set<String> keys = new HashSet<>();
        for (CompetitionTeamInfoMatch m : matchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(league.getId(), (long) round, "1")) {
            keys.add(MatchPlanService.competitionFixtureKey(m.getId()));
        }
        return keys.stream().filter(k -> matchPlanRepository.findByFixtureKey(k).isPresent()).count();
    }
}
