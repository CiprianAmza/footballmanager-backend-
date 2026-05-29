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
import com.footballmanagergamesimulator.service.MatchRoundSimulator;
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
    }

    // ==================== helpers ====================

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
