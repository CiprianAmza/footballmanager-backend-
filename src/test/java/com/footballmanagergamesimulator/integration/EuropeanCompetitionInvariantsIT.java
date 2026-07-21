package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.model.ClubCoefficient;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.ClubCoefficientRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.EuropeanCoefficientService;
import com.footballmanagergamesimulator.service.EuropeanCompetitionService;
import com.footballmanagergamesimulator.service.EuropeanDisplayService;
import com.footballmanagergamesimulator.testutil.BracketUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link EuropeanCompetitionService} — the LoC + Stars Cup
 * lifecycle (group draw, group-stage fixtures, group→QF qualification, 3rd-place
 * drop to Stars Cup, knockout propagation), the coefficient awarding matrix, the
 * rolling 5-season club coefficient, and the per-rank European spot allocation.
 *
 * <p>These tests run against a real Spring context (single bootstrap per test
 * class) so the seeded competitions/teams from {@code BootstrapService} are
 * available. Each test wipes the European state ({@code @BeforeEach}) so they
 * are order-independent and self-contained — only the bootstrap-seeded league
 * teams + competitions are shared.
 *
 * <p>Why this exists (Sesiunea 6 prep): the upcoming split of
 * {@link EuropeanCompetitionService} into 2-3 narrower services needs a safety
 * net. The existing {@code SeasonTransitionInvariantsTest} covers only the
 * season-boundary qualification flow (LoC/SC ranks, cup pass-through);
 * {@code EuropeanCompetitionServiceTest} covers only the static
 * {@code isKnockoutRound} classification. This file fills the gap: in-season
 * mechanics + coefficient math.
 *
 * <p>Concrete regressions guarded against:
 * <ul>
 *   <li>LoC group draw distributes 16 teams across 4 groups × 4 pots, with one
 *       team per pot per group.</li>
 *   <li>{@code generateGroupStageFixtures} produces rounds 2-7 for LoC (offset
 *       1) with 8 matches per round (2 per group × 4 groups) — a double
 *       round-robin per group.</li>
 *   <li>Top 2 of each LoC group advance to round 8 (QF); 3rd place drops to
 *       Stars Cup round 7 (playoff).</li>
 *   <li>Stars Cup group winner → QF (round 8), runner-up → playoff (round 7).</li>
 *   <li>LoC preliminary/qualifying losers drop into Stars Cup group stage
 *       (round 1), idempotent — second invocation does not duplicate.</li>
 *   <li>Per-match coefficient awarding matches the documented points matrix
 *       (LoC group win=2, draw=1, QF=3, SF=4, Final=5; SC group=1, draw=0.5,
 *       playoff=1, QF=1.5, SF=2, Final=2.5).</li>
 *   <li>League/cup matches award zero coefficient points (no-op).</li>
 *   <li>Rolling coefficient sums points in the [season-4, season] window only.</li>
 *   <li>Per-rank allocation matrix (rank 1 → 4 LoC + 2 SC, rank 7 → 2 preliminary
 *       + 1 SC, rank 8+ → none) matches the documented spread.</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("European invariants — LoC + Stars Cup lifecycle, coefficient awarding, allocation")
class EuropeanCompetitionInvariantsIT {

    @Autowired private EuropeanCompetitionService europeanCompetitionService;
    @Autowired private EuropeanCoefficientService europeanCoefficientService;
    @Autowired private EuropeanDisplayService europeanDisplayService;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository ctiRepository;
    @Autowired private CompetitionTeamInfoMatchRepository ctimRepository;
    @Autowired private TeamCompetitionDetailRepository tcdRepository;
    @Autowired private ClubCoefficientRepository clubCoefficientRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;

    private long locId;
    private long scId;
    private List<Competition> leagues;
    private long cupId;

    @BeforeEach
    void resolveCompetitionIdsAndClean() {
        List<Competition> all = competitionRepository.findAll();
        locId = all.stream().filter(c -> c.getTypeId() == 4)
                .map(Competition::getId).findFirst().orElseThrow();
        scId = all.stream().filter(c -> c.getTypeId() == 5)
                .map(Competition::getId).findFirst().orElseThrow();
        cupId = all.stream().filter(c -> c.getTypeId() == 2)
                .map(Competition::getId).findFirst().orElseThrow();
        leagues = all.stream().filter(c -> c.getTypeId() == 1).collect(Collectors.toList());

        // Reset Round to (season=1, round=1) — defends against context-level state
        // leakage: Spring caches the bootstrap context across @SpringBootTest classes
        // in the same JVM, and SeasonTransitionInvariantsIT advances the season to 2
        // before this class may run. Resetting here means our seeds + assertions
        // (which all reference season=1) are order-independent.
        Round round = roundRepository.findById(1L).orElseThrow();
        round.setSeason(1);
        round.setRound(1);
        roundRepository.save(round);

        // Wipe European state — bootstrap doesn't seed LoC/SC, so we are
        // cleaning up cross-test leakage (each test seeds what it needs).
        List<CompetitionTeamInfo> europeanCti = ctiRepository.findAll().stream()
                .filter(cti -> cti.getCompetitionId() == locId || cti.getCompetitionId() == scId)
                .toList();
        ctiRepository.deleteAll(europeanCti);

        List<CompetitionTeamInfoMatch> europeanFixtures = ctimRepository.findAll().stream()
                .filter(m -> m.getCompetitionId() == locId || m.getCompetitionId() == scId)
                .toList();
        ctimRepository.deleteAll(europeanFixtures);

        List<TeamCompetitionDetail> europeanTcd = tcdRepository.findAll().stream()
                .filter(tcd -> tcd.getCompetitionId() == locId || tcd.getCompetitionId() == scId)
                .toList();
        tcdRepository.deleteAll(europeanTcd);

        clubCoefficientRepository.deleteAll();
    }

    // ============================================================
    //  1. Coefficient awarding — matrix per (competition, round, result)
    // ============================================================

    @Test
    @DisplayName("awardCoefficientPoints: LoC preliminary/qualifying wins = 1 pt; loser gets 0")
    void awardCoefficientPoints_locPreliminaryAndQualifying() {
        long t1 = pickTeams(2).get(0);
        long t2 = pickTeams(2).get(1);

        // Round 0 (preliminary): t1 wins → +1
        europeanCoefficientService.awardCoefficientPoints(locId, 0L, t1, t2, 2, 0);
        // Round 1 (qualifying): t1 wins → +1
        europeanCoefficientService.awardCoefficientPoints(locId, 1L, t1, t2, 3, 1);

        assertEquals(2.0, coefficient(t1, 1), 0.001,
                "preliminary + qualifying wins = 1 + 1 = 2");
        assertEquals(0.0, coefficient(t2, 1), 0.001,
                "knockout loser gets 0 — no draw points in preliminary/qualifying");
    }

    @Test
    @DisplayName("awardCoefficientPoints: LoC group stage — win=2, draw=1")
    void awardCoefficientPoints_locGroupStage() {
        long t1 = pickTeams(2).get(0);
        long t2 = pickTeams(2).get(1);

        // Round 2 (first group match): t1 wins → +2
        europeanCoefficientService.awardCoefficientPoints(locId, 2L, t1, t2, 2, 0);
        // Round 5 (mid group): draw → +1 each
        europeanCoefficientService.awardCoefficientPoints(locId, 5L, t1, t2, 1, 1);

        assertEquals(3.0, coefficient(t1, 1), 0.001, "win(2) + draw(1) = 3");
        assertEquals(1.0, coefficient(t2, 1), 0.001, "loss(0) + draw(1) = 1");
    }

    @Test
    @DisplayName("awardCoefficientPoints: LoC knockout — QF=3, SF=4, Final=5")
    void awardCoefficientPoints_locKnockoutRounds() {
        long t1 = pickTeams(2).get(0);
        long t2 = pickTeams(2).get(1);

        europeanCoefficientService.awardCoefficientPoints(locId, 8L,  t1, t2, 2, 0); // QF: +3
        europeanCoefficientService.awardCoefficientPoints(locId, 9L,  t1, t2, 2, 1); // SF: +4
        europeanCoefficientService.awardCoefficientPoints(locId, 10L, t1, t2, 3, 1); // Final: +5

        assertEquals(12.0, coefficient(t1, 1), 0.001, "QF(3) + SF(4) + Final(5) = 12");
        assertEquals(0.0,  coefficient(t2, 1), 0.001, "knockout loser gets nothing");
    }

    @Test
    @DisplayName("awardCoefficientPoints: Stars Cup matrix (group=1, draw=0.5, playoff=1, QF=1.5, SF=2, Final=2.5)")
    void awardCoefficientPoints_starsCupMatrix() {
        long t1 = pickTeams(2).get(0);
        long t2 = pickTeams(2).get(1);

        europeanCoefficientService.awardCoefficientPoints(scId, 1L,  t1, t2, 2, 0); // group win: +1
        europeanCoefficientService.awardCoefficientPoints(scId, 3L,  t1, t2, 1, 1); // group draw: +0.5 each
        europeanCoefficientService.awardCoefficientPoints(scId, 7L,  t1, t2, 2, 1); // playoff: +1
        europeanCoefficientService.awardCoefficientPoints(scId, 8L,  t1, t2, 3, 2); // QF: +1.5
        europeanCoefficientService.awardCoefficientPoints(scId, 9L,  t1, t2, 1, 0); // SF: +2
        europeanCoefficientService.awardCoefficientPoints(scId, 10L, t1, t2, 4, 2); // Final: +2.5

        assertEquals(8.5, coefficient(t1, 1), 0.001,
                "1 + 0.5 + 1 + 1.5 + 2 + 2.5 = 8.5 across the SC matrix");
        assertEquals(0.5, coefficient(t2, 1), 0.001,
                "only the group draw awards points to the loser (0.5)");
    }

    @Test
    @DisplayName("awardCoefficientPoints: league + cup matches do NOT generate coefficient rows")
    void awardCoefficientPoints_nonEuropeanCompetitions_areNoOp() {
        long t1 = pickTeams(2).get(0);
        long t2 = pickTeams(2).get(1);
        long leagueId = leagues.get(0).getId();

        europeanCoefficientService.awardCoefficientPoints(leagueId, 1L, t1, t2, 3, 0);
        europeanCoefficientService.awardCoefficientPoints(cupId,    2L, t1, t2, 2, 0);

        assertEquals(0, clubCoefficientRepository.findAll().size(),
                "league + cup competitions should not produce coefficient rows");
    }

    // ============================================================
    //  2. Rolling coefficient — [currentSeason-4, currentSeason] window
    // ============================================================

    @Test
    @DisplayName("getClubCoefficientRolling: 5-season inclusive window, ignores seasons outside it")
    void getClubCoefficientRolling_window() {
        long teamId = pickTeams(1).get(0);
        seedCoefficient(teamId, 1, 1.0);
        seedCoefficient(teamId, 3, 2.0);
        seedCoefficient(teamId, 5, 3.0);
        seedCoefficient(teamId, 7, 4.0);
        seedCoefficient(teamId, 9, 5.0);

        // currentSeason=5 → window [1, 5] → 1 + 2 + 3 = 6
        assertEquals(6.0, europeanCoefficientService.getClubCoefficientRolling(teamId, 5), 0.001,
                "window [1,5] inclusive sums seasons 1, 3, 5");

        // currentSeason=9 → window [5, 9] → 3 + 4 + 5 = 12
        assertEquals(12.0, europeanCoefficientService.getClubCoefficientRolling(teamId, 9), 0.001,
                "window [5,9] inclusive sums seasons 5, 7, 9");

        // currentSeason=15 → window [11, 15] → none seeded → 0
        assertEquals(0.0, europeanCoefficientService.getClubCoefficientRolling(teamId, 15), 0.001,
                "window [11,15] excludes all seeded seasons → 0");

        // currentSeason=1 → window [max(1, -3), 1] = [1, 1] → 1.0
        assertEquals(1.0, europeanCoefficientService.getClubCoefficientRolling(teamId, 1), 0.001,
                "window clamps lower bound to 1 (no negative season lookups)");
    }

    // ============================================================
    //  3. LoC group draw + fixture generation
    // ============================================================

    @Test
    @DisplayName("drawEuropeanGroups: 16 LoC qualifiers → 4 groups, each with one team per pot")
    void drawEuropeanGroups_locFourGroupsOfFour() {
        List<Long> qualifiers = pickTeams(16);
        for (long teamId : qualifiers) {
            saveCti(teamId, locId, 2, 1, 0, 0);
        }

        europeanCompetitionService.drawEuropeanGroups(locId, 2);

        List<CompetitionTeamInfo> drawn = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == locId && cti.getRound() == 2)
                .toList();
        assertEquals(16, drawn.size(), "all 16 LoC qualifiers should be retained");

        Map<Integer, List<CompetitionTeamInfo>> byGroup = drawn.stream()
                .collect(Collectors.groupingBy(CompetitionTeamInfo::getGroupNumber));
        assertEquals(4, byGroup.size(), "should produce exactly 4 groups");

        for (Map.Entry<Integer, List<CompetitionTeamInfo>> e : byGroup.entrySet()) {
            int group = e.getKey();
            assertTrue(group >= 1 && group <= 4, "group number must be in 1..4 (got " + group + ")");
            assertEquals(4, e.getValue().size(), "group " + group + " should have 4 teams");

            Set<Integer> potsInGroup = e.getValue().stream()
                    .map(CompetitionTeamInfo::getPotNumber)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(1, 2, 3, 4), potsInGroup,
                    "group " + group + " should contain one team from each of pots 1-4, got " + potsInGroup);
        }
    }

    @Test
    @DisplayName("drawEuropeanGroups: fewer than 4 entries → no-op (no groups created)")
    void drawEuropeanGroups_belowMinimum_isNoOp() {
        for (long teamId : pickTeams(3)) {
            saveCti(teamId, locId, 2, 1, 0, 0);
        }

        europeanCompetitionService.drawEuropeanGroups(locId, 2);

        List<CompetitionTeamInfo> drawn = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == locId && cti.getRound() == 2)
                .toList();
        assertEquals(3, drawn.size(), "the 3 entries remain present");
        for (CompetitionTeamInfo cti : drawn) {
            assertEquals(0, cti.getGroupNumber(),
                    "groupNumber must stay 0 — too few teams to draw groups");
        }
    }

    @Test
    @DisplayName("drawEuropeanGroups: under-filled field keeps the configured group count (empty slots)")
    void drawEuropeanGroups_underfilled_keepsConfiguredGroupCount() {
        // 10 teams into a 4×4 format → still 4 groups (the configured shape), with the
        // 6 surplus slots left empty rather than collapsing to fewer groups.
        List<Long> ten = pickTeams(10);
        for (long teamId : ten) saveCti(teamId, locId, 2, 1, 0, 0);

        europeanCompetitionService.drawEuropeanGroups(locId, 2);

        List<CompetitionTeamInfo> grouped = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == locId && cti.getRound() == 2 && cti.getGroupNumber() > 0)
                .toList();
        assertEquals(10, grouped.stream().map(CompetitionTeamInfo::getTeamId).distinct().count(),
                "all 10 real teams must be placed (no placeholder rows persisted)");
        Set<Integer> groupNumbers = grouped.stream().map(CompetitionTeamInfo::getGroupNumber)
                .collect(Collectors.toSet());
        assertEquals(4, groupNumbers.size(), "configured group count (4) is preserved");
        for (int g : groupNumbers) {
            long inGroup = grouped.stream().filter(c -> c.getGroupNumber() == g)
                    .map(CompetitionTeamInfo::getTeamId).distinct().count();
            assertTrue(inGroup <= 4, "group " + g + " must not exceed the group size");
        }
    }

    @Test
    @DisplayName("generateGroupStageFixtures: LoC groups produce rounds 2-7, 8 matches each (double round-robin)")
    void generateGroupStageFixtures_locProducesRounds2to7() {
        seedGroupAssignment(pickTeams(16), locId);

        europeanCompetitionService.generateGroupStageFixtures(locId);

        // Each group: 4 teams × double round-robin = 12 matches over 6 matchdays.
        // LoC offset=1 → matchdays land on rounds 2..7.
        for (long round = 2; round <= 7; round++) {
            int matches = ctimRepository
                    .findAllByCompetitionIdAndRoundAndSeasonNumber(locId, round, "1").size();
            assertEquals(8, matches,
                    "LoC round " + round + " should have 8 fixtures (2 per group × 4 groups), got " + matches);
        }
        // Round 8+ is knockout — no group fixtures should be generated here.
        assertTrue(
                ctimRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(locId, 8L, "1").isEmpty(),
                "round 8 (QF) must NOT be populated by generateGroupStageFixtures");
    }

    @Test
    @DisplayName("generateGroupStageFixtures: Stars Cup groups land on rounds 1-6 (offset 0)")
    void generateGroupStageFixtures_starsCupRounds1to6() {
        seedGroupAssignment(pickTeams(16), scId);

        europeanCompetitionService.generateGroupStageFixtures(scId);

        for (long round = 1; round <= 6; round++) {
            int matches = ctimRepository
                    .findAllByCompetitionIdAndRoundAndSeasonNumber(scId, round, "1").size();
            assertEquals(8, matches,
                    "Stars Cup round " + round + " should have 8 fixtures, got " + matches);
        }
        assertTrue(
                ctimRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(scId, 7L, "1").isEmpty(),
                "Stars Cup round 7 (playoff) must NOT be populated as part of the group stage");
    }

    @Test
    @DisplayName("generateGroupStageFixtures: group of 4 == BracketUtil.GROUP_SCHEDULE (same complete double round-robin)")
    void generateGroupStageFixtures_matchesTestSchedule_forGroupOfFour() {
        // Parity guard: production builds group fixtures from RoundRobin + a leg
        // flip (EuropeanCompetitionService.generateGroupStageFixtures), while the
        // outcome-simulation tests use the hardcoded BracketUtil.GROUP_SCHEDULE.
        // The two MAY order the matchdays differently, but for a group of 4 they
        // must yield the identical set of home/away fixtures (a complete double
        // round-robin). This test pins that equivalence so the two engines can't
        // silently drift apart.
        List<Long> four = pickTeams(4);
        for (int i = 0; i < 4; i++) {
            saveCti(four.get(i), locId, 2, 1, 1, i + 1); // single group (1), pots 1..4
        }

        europeanCompetitionService.generateGroupStageFixtures(locId);

        // Production fixtures across LoC group rounds (offset 1 → rounds 2..7).
        Set<String> productionPairs = new HashSet<>();
        int productionCount = 0;
        for (long round = 2; round <= 7; round++) {
            for (CompetitionTeamInfoMatch m : ctimRepository
                    .findAllByCompetitionIdAndRoundAndSeasonNumber(locId, round, "1")) {
                productionPairs.add(m.getTeam1Id() + ">" + m.getTeam2Id());
                productionCount++;
            }
        }

        // Canonical complete double round-robin: every ordered (home, away) pair
        // of the 4 teams exactly once = 4 * 3 = 12 fixtures.
        Set<String> expected = new HashSet<>();
        for (long h : four) for (long a : four) if (h != a) expected.add(h + ">" + a);

        assertEquals(12, productionCount, "group of 4 must yield 12 fixtures (double round-robin)");
        assertEquals(expected, productionPairs,
                "production group fixtures must be the complete set of ordered home/away pairs");

        // The test engine's hardcoded schedule, mapped onto the same 4 teams,
        // must produce the identical fixture set — proving parity.
        Set<String> testSchedulePairs = new HashSet<>();
        for (int[][] matchday : BracketUtil.GROUP_SCHEDULE) {
            for (int[] match : matchday) {
                testSchedulePairs.add(four.get(match[0]) + ">" + four.get(match[1]));
            }
        }
        assertEquals(productionPairs, testSchedulePairs,
                "BracketUtil.GROUP_SCHEDULE must produce the same fixture set as production");
    }

    // ============================================================
    //  4. LoC group → QF + 3rd place → Stars Cup drop
    // ============================================================

    @Test
    @DisplayName("qualifyFromGroupStage: top 2 per LoC group → round 8 (QF); 3rd → Stars Cup round 7 (playoff)")
    void qualifyFromGroupStage_top2ToQF_thirdToStarsCup() {
        seedGroupAssignment(pickTeams(16), locId);

        // Standings: rank 0 (best) → 9 pts, rank 1 → 6, rank 2 → 3, rank 3 → 0
        Map<Integer, List<CompetitionTeamInfo>> byGroup = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == locId && cti.getGroupNumber() > 0)
                .sorted(Comparator.comparingInt(CompetitionTeamInfo::getPotNumber))
                .collect(Collectors.groupingBy(CompetitionTeamInfo::getGroupNumber));
        for (Map.Entry<Integer, List<CompetitionTeamInfo>> e : byGroup.entrySet()) {
            int rank = 0;
            for (CompetitionTeamInfo cti : e.getValue()) {
                seedTcd(cti.getTeamId(), locId, (3 - rank) * 3, rank);
                rank++;
            }
        }

        europeanCompetitionService.qualifyFromGroupStage(locId);

        Set<Long> qfTeams = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == locId && cti.getRound() == 8)
                .map(CompetitionTeamInfo::getTeamId).collect(Collectors.toSet());
        assertEquals(8, qfTeams.size(), "8 LoC QF qualifiers (top 2 per group × 4 groups)");

        Set<Long> scPlayoffTeams = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == scId && cti.getRound() == 7)
                .map(CompetitionTeamInfo::getTeamId).collect(Collectors.toSet());
        assertEquals(4, scPlayoffTeams.size(), "4 SC playoff entries (3rd from each LoC group)");

        Set<Long> intersection = new HashSet<>(qfTeams);
        intersection.retainAll(scPlayoffTeams);
        assertTrue(intersection.isEmpty(),
                "no team should be in both LoC QF and SC playoff; overlap: " + intersection);
    }

    // ============================================================
    //  5. Stars Cup group → QF (winner) + playoff (runner-up)
    // ============================================================

    @Test
    @DisplayName("qualifyFromStarsCupGroupStage: group winner → round 8 (QF), runner-up → round 7 (playoff)")
    void qualifyFromStarsCupGroupStage_winnerAndRunnerUp() {
        seedGroupAssignment(pickTeams(16), scId);

        Map<Integer, List<CompetitionTeamInfo>> byGroup = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == scId && cti.getGroupNumber() > 0)
                .sorted(Comparator.comparingInt(CompetitionTeamInfo::getPotNumber))
                .collect(Collectors.groupingBy(CompetitionTeamInfo::getGroupNumber));
        for (Map.Entry<Integer, List<CompetitionTeamInfo>> e : byGroup.entrySet()) {
            int rank = 0;
            for (CompetitionTeamInfo cti : e.getValue()) {
                seedTcd(cti.getTeamId(), scId, (4 - rank) * 3, rank);
                rank++;
            }
        }

        europeanCompetitionService.qualifyFromStarsCupGroupStage(scId);

        long winnersToQF = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == scId && cti.getRound() == 8).count();
        long runnersUpToPlayoff = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == scId && cti.getRound() == 7).count();

        assertEquals(4, winnersToQF, "4 SC group winners → QF");
        assertEquals(4, runnersUpToPlayoff, "4 SC runners-up → playoff");
    }

    // ============================================================
    //  6. LoC losers → Stars Cup (re-allocation between European tiers)
    // ============================================================

    @Test
    @DisplayName("assignLocLosersToStarsCup: preliminary losers drop into SC round 1")
    void assignLocLosersToStarsCup_preliminaryLosersDropToSC() {
        List<Long> teams = pickTeams(4);
        // 4 teams in LoC preliminary (round 0)
        for (long t : teams) saveCti(t, locId, 0, 1, 0, 0);
        // 2 winners advance to LoC qualifying (round 1)
        for (int i = 0; i < 2; i++) saveCti(teams.get(i), locId, 1, 1, 0, 0);

        europeanCompetitionService.assignLocLosersToStarsCup(locId, 0);

        Set<Long> scEntries = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == scId && cti.getRound() == 1)
                .map(CompetitionTeamInfo::getTeamId).collect(Collectors.toSet());
        assertEquals(2, scEntries.size(), "2 preliminary losers should be in SC round 1");
        assertEquals(Set.of(teams.get(2), teams.get(3)), scEntries,
                "the 2 teams without a round-1 advance should be the ones dropped to SC");
    }

    @Test
    @DisplayName("assignLocLosersToStarsCup: idempotent — second invocation does not duplicate")
    void assignLocLosersToStarsCup_idempotent() {
        List<Long> teams = pickTeams(4);
        for (long t : teams) saveCti(t, locId, 0, 1, 0, 0);
        for (int i = 0; i < 2; i++) saveCti(teams.get(i), locId, 1, 1, 0, 0);

        europeanCompetitionService.assignLocLosersToStarsCup(locId, 0);
        europeanCompetitionService.assignLocLosersToStarsCup(locId, 0); // second call

        long scEntries = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == scId && cti.getRound() == 1)
                .count();
        assertEquals(2, scEntries,
                "the alreadyInStarsCup guard must prevent duplicates on re-invocation");
    }

    @Test
    @DisplayName("assignLocLosersToStarsCup: qualifying losers (round 1 → round 2 advance) drop to SC")
    void assignLocLosersToStarsCup_qualifyingLosersDropToSC() {
        List<Long> teams = pickTeams(8);
        // 8 teams in LoC qualifying (round 1)
        for (long t : teams) saveCti(t, locId, 1, 1, 0, 0);
        // 4 winners advance to LoC group stage (round 2)
        for (int i = 0; i < 4; i++) saveCti(teams.get(i), locId, 2, 1, 0, 0);

        europeanCompetitionService.assignLocLosersToStarsCup(locId, 1);

        Set<Long> scEntries = ctiRepository.findAllBySeasonNumber(1).stream()
                .filter(cti -> cti.getCompetitionId() == scId && cti.getRound() == 1)
                .map(CompetitionTeamInfo::getTeamId).collect(Collectors.toSet());
        assertEquals(4, scEntries.size(), "4 qualifying-round losers should drop to SC");
        assertEquals(
                Set.of(teams.get(4), teams.get(5), teams.get(6), teams.get(7)),
                scEntries,
                "the 4 teams without a round-2 advance should be the ones dropped to SC");
    }

    // ============================================================
    //  7. Per-rank European spot allocation (display matrix)
    // ============================================================

    @Test
    @DisplayName("assignEuropeanAllocation: rank 1 → 4 LoC (3 group + 1 qualifying round 2) + 2 SC")
    void assignEuropeanAllocation_rank1() {
        Map<String, Object> entry = new LinkedHashMap<>();
        europeanDisplayService.assignEuropeanAllocation(entry, 1);
        assertEquals(4, entry.get("locSpots"));
        assertEquals("3 Group Stage + 1 Qualifying Round 2", entry.get("locEntry"));
        assertEquals(2, entry.get("starsCupSpots"));
    }

    @Test
    @DisplayName("assignEuropeanAllocation: rank 5 → 3 LoC (1 group + 2 qualifying round 2) + 1 SC")
    void assignEuropeanAllocation_rank5() {
        Map<String, Object> entry = new LinkedHashMap<>();
        europeanDisplayService.assignEuropeanAllocation(entry, 5);
        assertEquals(3, entry.get("locSpots"));
        assertEquals("1 Group Stage + 2 Qualifying Round 2", entry.get("locEntry"));
        assertEquals(1, entry.get("starsCupSpots"));
    }

    @Test
    @DisplayName("assignEuropeanAllocation: rank 7 → 2 qualifying round 1 + 1 SC; rank 8+ → none")
    void assignEuropeanAllocation_rank7AndBeyond() {
        Map<String, Object> r7 = new LinkedHashMap<>();
        europeanDisplayService.assignEuropeanAllocation(r7, 7);
        assertEquals(2, r7.get("locSpots"));
        assertEquals("2 Qualifying Round 1", r7.get("locEntry"));
        assertEquals(1, r7.get("starsCupSpots"));

        Map<String, Object> r8 = new LinkedHashMap<>();
        europeanDisplayService.assignEuropeanAllocation(r8, 8);
        assertEquals(0, r8.get("locSpots"));
        assertEquals("None", r8.get("locEntry"));
        assertEquals(0, r8.get("starsCupSpots"));
    }

    @Test
    @DisplayName("getEuropeanSummary: LoC 16-team group stage + 4 groups × 4, top 2 advance; SC parallel")
    void getEuropeanSummary_structuralTotals() {
        Map<String, Object> summary = europeanDisplayService.getEuropeanSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> loc = (Map<String, Object>) summary.get("loc");
        assertEquals(16, loc.get("groupStageTeams"));
        assertEquals(4, loc.get("groups"));
        assertEquals(4, loc.get("teamsPerGroup"));
        assertEquals(2, loc.get("advancePerGroup"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sc = (Map<String, Object>) summary.get("starsCup");
        assertEquals(16, sc.get("totalTeams"));
        assertEquals(4, sc.get("groups"));
        assertEquals(4, sc.get("teamsPerGroup"));
    }

    // ============================================================
    //  8. League ranking by coefficient (used to seed the qualifier matrix)
    // ============================================================

    @Test
    @DisplayName("getLeagueIdsSortedByCoefficient: leagues with European points outrank those without")
    void getLeagueIdsSortedByCoefficient_leaguesWithCoefficientRankHigher() {
        // Sanity: at least 2 leagues exist (project memory: 7 leagues, with hardcoded Kess pair 3/5).
        assertTrue(leagues.size() >= 2,
                "this test needs at least 2 leagues from bootstrap");

        // Pick two distinct leagues from different nations. Then award a large
        // coefficient to a team in nation A this season — those points should
        // make league A outrank league B in the sort.
        Competition leagueA = leagues.get(0);
        Competition leagueB = leagues.stream()
                .filter(l -> l.getNationId() != leagueA.getNationId())
                .findFirst().orElseThrow(
                        () -> new AssertionError("need two leagues from different nations"));

        // Pick a team from each league. Bootstrap assigns Team.competitionId.
        long teamInA = teamRepository.findAll().stream()
                .filter(t -> t.getCompetitionId() == leagueA.getId())
                .map(Team::getId).findFirst().orElseThrow();
        long teamInB = teamRepository.findAll().stream()
                .filter(t -> t.getCompetitionId() == leagueB.getId())
                .map(Team::getId).findFirst().orElseThrow();

        // Register each team as a European entry (CTI for LoC) — required so
        // getLeagueIdsSortedByCoefficient counts their points toward their nation.
        saveCti(teamInA, locId, 2, 1, 0, 0);
        saveCti(teamInB, locId, 2, 1, 0, 0);

        // Seed: team A has a large coefficient, team B has none. We pick a value
        // well above the reputation/100 fallback (typical league averages run
        // ~50-90), so league A's real coefficient definitively outranks the
        // reputation-only ordering applied to leagues without seeded points.
        seedCoefficient(teamInA, 1, 1000.0);

        List<Long> sorted = europeanCoefficientService.getLeagueIdsSortedByCoefficient();
        assertFalse(sorted.isEmpty(), "league ranking should produce a non-empty list");

        int posA = sorted.indexOf(leagueA.getId());
        int posB = sorted.indexOf(leagueB.getId());
        assertTrue(posA >= 0, "league A must appear in the ranking");
        assertTrue(posB >= 0, "league B must appear in the ranking");
        assertTrue(posA < posB,
                "league A (coefficient=100) must rank ABOVE league B (no coefficient); "
                        + "got posA=" + posA + ", posB=" + posB + ", order=" + sorted);
    }

    // ============================================================
    //  Helpers
    // ============================================================

    /** Coefficient persisted for {@code teamId} in {@code season}, 0 if absent. */
    private double coefficient(long teamId, int season) {
        return clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, season)
                .map(ClubCoefficient::getPoints).orElse(0.0);
    }

    /**
     * First {@code n} team IDs ordered ascending — deterministic across runs so
     * tests pick consistent teams from the bootstrap.
     */
    private List<Long> pickTeams(int n) {
        return teamRepository.findAll().stream()
                .sorted(Comparator.comparingLong(Team::getId))
                .limit(n)
                .map(Team::getId)
                .toList();
    }

    /** Persist a CompetitionTeamInfo row. */
    private void saveCti(long teamId, long competitionId, long round, long season, int groupNumber, int potNumber) {
        CompetitionTeamInfo cti = new CompetitionTeamInfo();
        cti.setTeamId(teamId);
        cti.setCompetitionId(competitionId);
        cti.setSeasonNumber(season);
        cti.setRound(round);
        cti.setGroupNumber(groupNumber);
        cti.setPotNumber(potNumber);
        ctiRepository.save(cti);
    }

    /**
     * Distribute {@code teamIds} into 4 groups × 4 pots for the given European
     * competition. Round = 1 for Stars Cup (offset 0), 2 for LoC (offset 1).
     */
    private void seedGroupAssignment(List<Long> teamIds, long competitionId) {
        int teamsPerGroup = 4;
        long startRound = (competitionId == scId) ? 1 : 2;
        for (int i = 0; i < teamIds.size(); i++) {
            int group = (i / teamsPerGroup) + 1;
            int pot = (i % teamsPerGroup) + 1;
            saveCti(teamIds.get(i), competitionId, startRound, 1, group, pot);
        }
    }

    /** Seed a TCD row for a team in a competition with the given points + losses. */
    private void seedTcd(long teamId, long competitionId, int points, int losses) {
        TeamCompetitionDetail tcd = tcdRepository.findFirstByTeamIdAndCompetitionId(teamId, competitionId);
        if (tcd == null) {
            tcd = new TeamCompetitionDetail();
            tcd.setTeamId(teamId);
            tcd.setCompetitionId(competitionId);
            tcd.setForm("");
        }
        tcd.setPoints(points);
        tcd.setWins(points / 3);
        tcd.setDraws(0);
        tcd.setLoses(losses);
        tcd.setGoalsFor(points);
        tcd.setGoalsAgainst(losses);
        tcd.setGoalDifference(points - losses);
        tcd.setGames((points / 3) + losses);
        tcdRepository.save(tcd);
    }

    /** Seed a ClubCoefficient row for a specific team + season. */
    private void seedCoefficient(long teamId, int season, double points) {
        ClubCoefficient cc = new ClubCoefficient();
        cc.setTeamId(teamId);
        cc.setSeasonNumber(season);
        cc.setPoints(points);
        clubCoefficientRepository.save(cc);
    }
}
