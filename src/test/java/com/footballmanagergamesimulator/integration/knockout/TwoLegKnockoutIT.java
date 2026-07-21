package com.footballmanagergamesimulator.integration.knockout;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.service.EuropeanCompetitionService;
import com.footballmanagergamesimulator.service.LiveMatchSession;
import com.footballmanagergamesimulator.service.LiveMatchSimulationService;
import com.footballmanagergamesimulator.service.MatchRoundSimulator;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-flow integration test for two-leg (home-and-away) knockout ties.
 * Exercises the real {@link MatchRoundSimulator#simulateRound} + European fixture
 * generation against the bootstrapped DB, verifying that:
 * <ul>
 *   <li>a two-leg round generates two fixtures per tie (swapped venues, shared tieId);</li>
 *   <li>leg 1 records a result but does NOT advance anyone;</li>
 *   <li>leg 2 aggregates both legs and advances exactly one winner (extra time /
 *       penalties resolve a level aggregate).</li>
 * </ul>
 *
 * <p>Each test is {@code @Transactional} so all writes (and the deletes that
 * isolate the round) roll back — no pollution of the shared Spring context DB.
 * The League of Champions (type 4) round 8 is two-leg by default config.
 */
@SpringBootTest
@Transactional
@DisplayName("Two-leg knockout — production flow: leg 1 defers, leg 2 decides on aggregate")
class TwoLegKnockoutIT {

    private static final long SEED = 20260528L;

    @Autowired private MatchRoundSimulator matchRoundSimulator;
    @Autowired private MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired private LiveMatchSimulationService liveMatchSimulationService;
    @Autowired private EuropeanCompetitionService europeanCompetitionService;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepo;
    @Autowired private CompetitionTeamInfoRepository ctiRepo;
    @Autowired private CompetitionTeamInfoDetailRepository detailRepo;
    @Autowired private MatchStatsRepository matchStatsRepo;
    @Autowired private RoundRepository roundRepository;

    @AfterEach
    void restoreRng() {
        matchRoundSimulator.setRandomForTesting(new Random());
    }

    @Test
    @DisplayName("Two-leg round draws two legs per tie (swapped venues, shared tieId)")
    void twoLegRoundGeneratesTwoLegsPerTie() {
        long loc = locCompetitionId();
        String season = currentSeason();
        // Isolate round 8 for this comp/season.
        matchRepo.deleteAll(matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(loc, 8, season));

        List<Long> participants = new ArrayList<>(List.of(1L, 2L, 3L, 4L));
        europeanCompetitionService.drawEuropeanKnockoutSeeded(loc, 8L, participants);

        List<CompetitionTeamInfoMatch> r8 = matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(loc, 8, season);
        // 4 participants → 2 pairings → 2 legs each = 4 matches.
        assertThat(r8).as("two legs per tie for a 4-team two-leg round").hasSize(4);

        Map<Long, List<CompetitionTeamInfoMatch>> byTie = new HashMap<>();
        for (CompetitionTeamInfoMatch m : r8) {
            assertThat(m.getTieId()).as("two-leg matches must carry a tieId").isNotZero();
            byTie.computeIfAbsent(m.getTieId(), k -> new ArrayList<>()).add(m);
        }
        assertThat(byTie).as("4 teams → 2 ties").hasSize(2);
        for (List<CompetitionTeamInfoMatch> legs : byTie.values()) {
            assertThat(legs).hasSize(2);
            CompetitionTeamInfoMatch leg1 = legs.stream().filter(m -> m.getLegNumber() == 1).findFirst().orElseThrow();
            CompetitionTeamInfoMatch leg2 = legs.stream().filter(m -> m.getLegNumber() == 2).findFirst().orElseThrow();
            assertThat(leg2.getTeam1Id()).as("leg 2 host = leg 1 visitor").isEqualTo(leg1.getTeam2Id());
            assertThat(leg2.getTeam2Id()).as("leg 2 visitor = leg 1 host").isEqualTo(leg1.getTeam1Id());
        }
    }

    @Test
    @DisplayName("Single-leg round (final) draws one match, no leg metadata")
    void singleLegRoundGeneratesOneMatch() {
        long loc = locCompetitionId();
        String season = currentSeason();
        matchRepo.deleteAll(matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(loc, 10, season));

        europeanCompetitionService.drawEuropeanKnockoutSeeded(loc, 10L, new ArrayList<>(List.of(1L, 2L)));

        List<CompetitionTeamInfoMatch> r10 = matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(loc, 10, season);
        assertThat(r10).as("the final is single-leg: one match for two teams").hasSize(1);
        assertThat(r10.get(0).getLegNumber()).isZero();
        assertThat(r10.get(0).getTieId()).isZero();
    }

    @Test
    @DisplayName("Leg 1 defers; leg 2 aggregates and advances exactly one winner")
    void twoLegTieDecidedOnAggregateLegOneDoesNotPropagate() {
        long loc = locCompetitionId();
        String season = currentSeason();
        long seasonLong = Long.parseLong(season);
        long teamA = 1L, teamB = 2L;
        long tieId = 999_999L;

        // Isolate round 8 (both legs) + round 9 (winners) + the two match-stat rows.
        matchRepo.deleteAll(matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(loc, 8, season));
        ctiRepo.deleteAll(ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(9, loc, seasonLong));
        detailRepo.deleteAll(detailRepo.findAllByCompetitionIdAndRoundIdAndSeasonNumber(loc, 8, seasonLong));
        matchStatsRepo.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(loc, (int) seasonLong, 8, teamA, teamB)
                .ifPresent(matchStatsRepo::delete);
        matchStatsRepo.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(loc, (int) seasonLong, 8, teamB, teamA)
                .ifPresent(matchStatsRepo::delete);

        matchRepo.save(leg(loc, season, teamA, teamB, 1, tieId)); // leg 1: A home
        matchRepo.save(leg(loc, season, teamB, teamA, 2, tieId)); // leg 2: B home

        matchRoundSimulator.setRandomForTesting(new Random(SEED));
        matchRoundSimulator.simulateRound(String.valueOf(loc), "8");

        List<CompetitionTeamInfoDetail> details =
                detailRepo.findAllByCompetitionIdAndRoundIdAndSeasonNumber(loc, 8, seasonLong);
        assertThat(details).as("both legs must be recorded as played matches").hasSize(2);

        List<CompetitionTeamInfo> advanced =
                ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(9, loc, seasonLong);
        assertThat(advanced).as("exactly one winner advances per two-leg tie (leg 1 must not propagate)").hasSize(1);
        assertThat(advanced.get(0).getTeamId()).as("winner is one of the two tie participants").isIn(teamA, teamB);

        assertThat(details.stream().anyMatch(d -> d.getScore() != null && d.getScore().contains("(agg")))
                .as("the second leg's result is annotated with the aggregate").isTrue();

        CompetitionTeamInfoDetail secondLegResult = details.stream()
                .filter(d -> d.getLegNumber() == 2).findFirst().orElseThrow();
        CompetitionTeamInfoMatch playedLeg1 = matchRepo.findByTieIdAndLegNumber(tieId, 1).orElseThrow();
        CompetitionTeamInfoMatch playedLeg2 = matchRepo.findByTieIdAndLegNumber(tieId, 2).orElseThrow();
        assertThat(secondLegResult.getAggregateTeam1Score())
                .as("aggregate is aligned with the second-leg home team")
                .isEqualTo(playedLeg1.getTeam2Score() + playedLeg2.getTeam1Score());
        assertThat(secondLegResult.getAggregateTeam2Score())
                .as("aggregate is aligned with the second-leg away team")
                .isEqualTo(playedLeg1.getTeam1Score() + playedLeg2.getTeam2Score());
        if ("PENALTIES".equals(secondLegResult.getDecidedBy())) {
            assertThat(secondLegResult.getPenaltyTeam1Score()).isNotEqualTo(secondLegResult.getPenaltyTeam2Score());
            assertThat(secondLegResult.getPenaltyTeam1Score() > secondLegResult.getPenaltyTeam2Score())
                    .isEqualTo(secondLegResult.getWinnerTeamId() == secondLegResult.getTeam1Id());
        }
    }

    @Test
    @DisplayName("Legs on separate days: leg 1 call persists score + defers; leg 2 call aggregates")
    void twoLegTieAcrossSeparateSimulateCalls() {
        long loc = locCompetitionId();
        String season = currentSeason();
        long seasonLong = Long.parseLong(season);
        long teamA = 1L, teamB = 2L;
        long tieId = 888_888L;

        matchRepo.deleteAll(matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(loc, 8, season));
        ctiRepo.deleteAll(ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(9, loc, seasonLong));
        detailRepo.deleteAll(detailRepo.findAllByCompetitionIdAndRoundIdAndSeasonNumber(loc, 8, seasonLong));
        matchStatsRepo.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(loc, (int) seasonLong, 8, teamA, teamB)
                .ifPresent(matchStatsRepo::delete);
        matchStatsRepo.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(loc, (int) seasonLong, 8, teamB, teamA)
                .ifPresent(matchStatsRepo::delete);

        matchRepo.save(leg(loc, season, teamA, teamB, 1, tieId)); // leg 1: A home
        matchRepo.save(leg(loc, season, teamB, teamA, 2, tieId)); // leg 2: B home

        matchRoundSimulator.setRandomForTesting(new Random(SEED));

        // --- Day 1: first leg only ---
        matchRoundSimulator.simulateRound(String.valueOf(loc), "8", 1);

        assertThat(ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(9, loc, seasonLong))
                .as("leg 1 alone must not advance anyone").isEmpty();
        CompetitionTeamInfoMatch leg1 = matchRepo.findByTieIdAndLegNumber(tieId, 1).orElseThrow();
        assertThat(leg1.getTeam1Score()).as("leg 1 score is persisted for cross-day aggregation").isGreaterThanOrEqualTo(0);

        // --- Day 2: second leg aggregates with the persisted first leg ---
        matchRoundSimulator.simulateRound(String.valueOf(loc), "8", 2);

        List<CompetitionTeamInfo> advanced =
                ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(9, loc, seasonLong);
        assertThat(advanced).as("leg 2 decides the tie and advances exactly one winner").hasSize(1);
        assertThat(advanced.get(0).getTeamId()).isIn(teamA, teamB);

        List<CompetitionTeamInfoDetail> details =
                detailRepo.findAllByCompetitionIdAndRoundIdAndSeasonNumber(loc, 8, seasonLong);
        assertThat(details.stream().anyMatch(d -> d.getScore() != null && d.getScore().contains("(agg")))
                .as("second leg result is annotated with the aggregate").isTrue();
    }

    @Test
    @DisplayName("Dispatch: leg-1 matchday draws both legs + defers; leg-2 matchday aggregates + advances")
    void simulateMatchdayDispatchesLegsSeparately() {
        long loc = locCompetitionId();
        String season = currentSeason();
        long seasonLong = Long.parseLong(season);
        int matchday = 9; // LoC matchday 9 → round 8 (QF, two-leg)

        // Isolate round 8 (fixtures + details) and round 9 (winners).
        matchRepo.deleteAll(matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(loc, 8, season));
        detailRepo.deleteAll(detailRepo.findAllByCompetitionIdAndRoundIdAndSeasonNumber(loc, 8, seasonLong));
        ctiRepo.deleteAll(ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(8, loc, seasonLong));
        ctiRepo.deleteAll(ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(9, loc, seasonLong));

        // Seed 4 participants at round 8 (the QF feed).
        for (long teamId : List.of(1L, 2L, 3L, 4L)) {
            CompetitionTeamInfo cti = new CompetitionTeamInfo();
            cti.setTeamId(teamId);
            cti.setCompetitionId(loc);
            cti.setSeasonNumber(seasonLong);
            cti.setRound(8);
            ctiRepo.save(cti);
        }

        matchRoundSimulator.setRandomForTesting(new Random(SEED));

        // --- Leg-1 matchday event: draws both legs, plays leg 1 only ---
        matchSimulationOrchestrator.simulateMatchday(loc, matchday, (int) seasonLong, 1);

        List<CompetitionTeamInfoMatch> r8 = matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(loc, 8, season);
        assertThat(r8).as("two ties × two legs = 4 matches drawn").hasSize(4);
        assertThat(ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(9, loc, seasonLong))
                .as("leg 1 must not advance anyone").isEmpty();

        // --- Leg-2 matchday event: aggregates + advances winners ---
        matchSimulationOrchestrator.simulateMatchday(loc, matchday, (int) seasonLong, 2);

        assertThat(ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(9, loc, seasonLong))
                .as("leg 2 advances exactly one winner per tie (2 ties → 2 winners)").hasSize(2);
    }

    // ==================== interactive (human-watched) commit path ====================

    @Test
    @DisplayName("Interactive commit, leg 1: persists the score and propagates nothing")
    void interactiveCommitLegOneDefersAndPersists() {
        long loc = locCompetitionId();
        String season = currentSeason();
        long seasonLong = Long.parseLong(season);
        int seasonInt = (int) seasonLong;
        long teamA = 1L, teamB = 2L;
        long tieId = 777_001L;
        int round = 8;

        isolateKnockout(loc, season, seasonLong, round, teamA, teamB);
        matchRepo.save(leg(loc, season, teamA, teamB, 1, tieId)); // leg 1: A home
        matchRepo.save(leg(loc, season, teamB, teamA, 2, tieId)); // leg 2: B home

        Map<String, Object> result = commitInteractive(loc, seasonInt, round, teamA, teamB, /*legNumber*/ 1, tieId);
        assertThat((String) result.get("knockoutResultText"))
                .as("leg 1 commit announces the return leg, decides nothing").contains("First leg");

        assertThat(ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(round + 1, loc, seasonLong))
                .as("interactive leg 1 must not advance anyone").isEmpty();

        CompetitionTeamInfoMatch leg1 = matchRepo.findByTieIdAndLegNumber(tieId, 1).orElseThrow();
        assertThat(leg1.getTeam1Score()).as("leg 1 score persisted on commit for cross-day aggregation")
                .isGreaterThanOrEqualTo(0);

        List<CompetitionTeamInfoDetail> details =
                detailRepo.findAllByCompetitionIdAndRoundIdAndSeasonNumber(loc, round, seasonLong);
        assertThat(details).hasSize(1);
        assertThat(details.get(0).getScore()).as("leg 1 result annotated as first leg").contains("(1st leg)");
        assertThat(details.get(0).getLegNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Interactive commit, leg 2: aggregates with persisted leg 1 and advances one winner")
    void interactiveCommitLegTwoAggregatesAndPropagates() {
        long loc = locCompetitionId();
        String season = currentSeason();
        long seasonLong = Long.parseLong(season);
        int seasonInt = (int) seasonLong;
        long teamA = 1L, teamB = 2L;
        long tieId = 777_002L;
        int round = 8;

        isolateKnockout(loc, season, seasonLong, round, teamA, teamB);
        // Leg 1 already played (1-1) on a previous calendar day.
        CompetitionTeamInfoMatch leg1 = leg(loc, season, teamA, teamB, 1, tieId);
        leg1.setTeam1Score(1);
        leg1.setTeam2Score(1);
        matchRepo.save(leg1);
        matchRepo.save(leg(loc, season, teamB, teamA, 2, tieId)); // leg 2: B home

        // Leg 2 host = teamB (tie side B); visitor = teamA (side A).
        Map<String, Object> result = commitInteractive(loc, seasonInt, round, teamB, teamA, /*legNumber*/ 2, tieId);
        assertThat((String) result.get("knockoutResultText"))
                .as("leg 2 commit announces who advanced (aggregate or penalties)")
                .containsAnyOf("aggregate", "penalties");

        List<CompetitionTeamInfo> advanced =
                ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(round + 1, loc, seasonLong);
        assertThat(advanced).as("interactive leg 2 decides the tie and advances exactly one winner").hasSize(1);
        assertThat(advanced.get(0).getTeamId()).isIn(teamA, teamB);

        List<CompetitionTeamInfoDetail> details =
                detailRepo.findAllByCompetitionIdAndRoundIdAndSeasonNumber(loc, round, seasonLong);
        assertThat(details.stream().anyMatch(d -> d.getScore() != null && d.getScore().contains("(agg")))
                .as("leg 2 result annotated with the aggregate").isTrue();
    }

    @Test
    @DisplayName("Interactive commit, single-leg knockout: advances the winner (was previously dropped)")
    void interactiveCommitSingleLegPropagatesWinner() {
        long loc = locCompetitionId();
        String season = currentSeason();
        long seasonLong = Long.parseLong(season);
        int seasonInt = (int) seasonLong;
        long teamA = 1L, teamB = 2L;
        int round = 10; // LoC final — single-leg

        isolateKnockout(loc, season, seasonLong, round, teamA, teamB);

        Map<String, Object> result = commitInteractive(loc, seasonInt, round, teamA, teamB, /*legNumber*/ 0, /*tieId*/ 0L);
        // Text is present only when the 90' was level (decided by ET/pens); null on a decisive scoreline.
        String koText = (String) result.get("knockoutResultText");
        if (koText != null) {
            assertThat(koText).as("single-leg ET/penalties outcome mentions the winner")
                    .containsAnyOf("extra time", "penalties");
        }

        List<CompetitionTeamInfo> advanced =
                ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(round + 1, loc, seasonLong);
        assertThat(advanced).as("a single-leg knockout winner must propagate on interactive commit").hasSize(1);
        assertThat(advanced.get(0).getTeamId()).isIn(teamA, teamB);
    }

    // ==================== helpers ====================

    /** Drive a real interactive session to full time, then commit it; returns the commit result map. */
    private Map<String, Object> commitInteractive(long comp, int season, int round,
                                                  long teamId1, long teamId2, int legNumber, long tieId) {
        String key = LiveMatchSimulationService.buildKey(comp, season, round, teamId1, teamId2);
        LiveMatchSession session = liveMatchSimulationService.createInteractiveSession(
                teamId1, teamId2, 1500.0, 1400.0, comp, season, round, false);
        session.setDeferredContext(1500.0, 1400.0, "442", "442", null, null, true, legNumber, tieId, 0);
        session.advanceUntilAndSnapshot(session.getTotalMinutes());
        assertThat(session.isFinished()).as("session reaches full time before commit").isTrue();
        return matchSimulationOrchestrator.finalizeInteractiveLiveMatch(key);
    }

    /** Clear a knockout round's fixtures/details + the next round's winners + this
     *  match's stats rows so the interactive commit runs against a clean slate. */
    private void isolateKnockout(long loc, String season, long seasonLong, int round, long teamA, long teamB) {
        matchRepo.deleteAll(matchRepo.findAllByCompetitionIdAndRoundAndSeasonNumber(loc, round, season));
        detailRepo.deleteAll(detailRepo.findAllByCompetitionIdAndRoundIdAndSeasonNumber(loc, round, seasonLong));
        ctiRepo.deleteAll(ctiRepo.findAllByRoundAndCompetitionIdAndSeasonNumber(round + 1, loc, seasonLong));
        matchStatsRepo.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(loc, (int) seasonLong, round, teamA, teamB)
                .ifPresent(matchStatsRepo::delete);
        matchStatsRepo.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(loc, (int) seasonLong, round, teamB, teamA)
                .ifPresent(matchStatsRepo::delete);
    }

    private CompetitionTeamInfoMatch leg(long comp, String season, long home, long away, int legNumber, long tieId) {
        CompetitionTeamInfoMatch m = new CompetitionTeamInfoMatch();
        m.setCompetitionId(comp);
        m.setRound(8);
        m.setTeam1Id(home);
        m.setTeam2Id(away);
        m.setSeasonNumber(season);
        m.setLegNumber(legNumber);
        m.setTieId(tieId);
        return m;
    }

    private long locCompetitionId() {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 4)
                .map(Competition::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No League of Champions (type 4) competition in bootstrap"));
    }

    private String currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).map(String::valueOf).orElse("1");
    }
}
