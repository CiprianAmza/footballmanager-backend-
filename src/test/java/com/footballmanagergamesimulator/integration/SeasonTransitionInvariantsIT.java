package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.EuropeanQualificationPolicy;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.SeasonObjective;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.SeasonObjectiveRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.SeasonTransitionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-season integration test: boots the Spring context, simulates a cup
 * knockout round to verify winner propagation, then seeds final standings,
 * fires {@code processEndOfSeason(1)} + {@code processNewSeasonSetup(1)}
 * and asserts the cross-cutting invariants of the season-transition flow.
 *
 * <p>Why composite tests: each {@code @SpringBootTest} class reuses one
 * Spring context, so the bootstrap is shared. End-of-season and new-season
 * setup mutate global state (round, competition team info, ages, regens),
 * so they're @Order'd and the asserts are grouped per phase.
 *
 * <p>What this guards against (concrete past+future regressions):
 * <ul>
 *   <li>Knockout propagation — round-N winner must appear in a round-(N+1)
 *       fixture; the cup bracket fill logic broke once already.</li>
 *   <li>League promotion/relegation hardcoded for the Kess pair
 *       (competition IDs 3 + 5). Bottom of comp 3 must move to comp 5
 *       next season; top of comp 5 must move to comp 3.</li>
 *   <li>LoC qualification per coefficient rank — rank-1 league gets
 *       4 LoC entries (3 direct to groups + 1 qualifying); rank-7 gets
 *       2 preliminary spots.</li>
 *   <li>Stars Cup reserves one spot per nation for the cup winner.
 *       If the cup winner is already in LoC, the spot passes to the
 *       first non-qualified league team — exact scenario the user
 *       flagged.</li>
 *   <li>Transfer window opens at the end of {@code processEndOfSeason}.</li>
 *   <li>New season starts: round.season +1, round.round = 1, players age +1,
 *       regens appear, personalized tactics cleared, new fixtures generated.</li>
 * </ul>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Season-transition invariants — knockout + end-of-season + new-season-setup")
class SeasonTransitionInvariantsIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private SeasonTransitionService seasonTransitionService;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository ctiRepository;
    @Autowired private CompetitionTeamInfoMatchRepository ctimRepository;
    @Autowired private TeamCompetitionDetailRepository tcdRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private SeasonObjectiveRepository seasonObjectiveRepository;
    @Autowired private CompetitionFormatConfig competitionFormatConfig;
    @Autowired private EuropeanQualificationPolicy europeanQualificationPolicy;

    // ============================================================
    //  Order 1 — bootstrap shape sanity
    // ============================================================

    @Test
    @Order(1)
    @DisplayName("Bootstrap: leagues + cups + second leagues + LoC + Stars Cup all exist")
    void bootstrap_competitionsHaveExpectedShape() {
        List<Competition> all = competitionRepository.findAll();
        Map<Long, Long> countByType = all.stream()
                .collect(Collectors.groupingBy(Competition::getTypeId, Collectors.counting()));

        // 1 = First League, 2 = National Cup, 3 = Second League, 4 = LoC, 5 = Stars Cup
        long leagueCount = countByType.getOrDefault(1L, 0L);
        long cupCount = countByType.getOrDefault(2L, 0L);
        long secondLeagueCount = countByType.getOrDefault(3L, 0L);
        assertTrue(leagueCount >= 1, "should have at least 1 first league");
        assertEquals(leagueCount, cupCount,
                "each first league should have a matching national cup (typeId=1 count must equal typeId=2 count)");
        assertEquals(1L, countByType.getOrDefault(4L, 0L),
                "exactly one League of Champions (typeId=4)");
        assertEquals(1L, countByType.getOrDefault(5L, 0L),
                "exactly one Stars Cup (typeId=5)");

        // Second-league count: project memory says 7 first leagues + 7 second leagues.
        // Don't hardcode 7 here — just assert the Kess pair (id 3 + id 5) exists,
        // since the hardcoded promotion/relegation logic in processEndOfSeason targets
        // those specific competition IDs.
        Optional<Competition> compId3 = competitionRepository.findById(3L);
        Optional<Competition> compId5 = competitionRepository.findById(5L);
        assertTrue(compId3.isPresent(), "competition id=3 (Kess First League) should exist — promotion/relegation logic depends on it");
        assertTrue(compId5.isPresent(), "competition id=5 (Kess Second League) should exist — promotion/relegation logic depends on it");
        assertEquals(compId3.get().getNationId(), compId5.get().getNationId(),
                "comp 3 and comp 5 must be from the same nation (Kess)");
        assertTrue(secondLeagueCount >= 1, "should have at least one second league");
    }

    // ============================================================
    //  Order 2 — knockout-round propagation: simulate a cup round-1
    //  and verify each match's winner is registered in round 2.
    // ============================================================

    @Test
    @Order(2)
    @DisplayName("Cup knockout: round-1 winners propagate into round-2 bracket")
    void cupKnockout_round1WinnersAdvanceToRound2() {
        Competition cup = findFirstCupWithRoundFixtures(1L);
        if (cup == null) {
            // Some bootstraps may not have round-1 fixtures pre-generated for cups
            // with N == M (auto-bracket). That's fine — try round 2 instead.
            cup = findFirstCupWithRoundFixtures(2L);
        }
        assertNotNull(cup, "bootstrap should have at least one cup with playable knockout fixtures");

        long startRound = ctimRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(cup.getId(), 1L, "1").isEmpty() ? 2L : 1L;
        List<CompetitionTeamInfoMatch> r1Matches = ctimRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(cup.getId(), startRound, "1");
        assertFalse(r1Matches.isEmpty(),
                "cup " + cup.getName() + " round " + startRound + " should have fixtures");

        // Capture the teams in each round-startRound match (so we can identify the winner)
        Map<Integer, long[]> matchTeams = new HashMap<>();
        for (CompetitionTeamInfoMatch m : r1Matches) {
            matchTeams.put(m.getMatchIndex(), new long[]{m.getTeam1Id(), m.getTeam2Id()});
        }

        // -------- ACT --------
        competitionController.simulateRound(String.valueOf(cup.getId()), String.valueOf(startRound));

        // For each round-startRound match, find which team won (via the detail row's score)
        // and verify the winner appears somewhere in round-(startRound+1).
        long nextRound = startRound + 1;
        List<CompetitionTeamInfo> nextRoundEntries = ctiRepository
                .findAllByRoundAndCompetitionIdAndSeasonNumber(nextRound, cup.getId(), 1L);
        List<CompetitionTeamInfoMatch> nextRoundMatches = ctimRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(cup.getId(), nextRound, "1");

        // The next round's participants — either via CompetitionTeamInfo (LoC/SC style)
        // or via CompetitionTeamInfoMatch teams (national cup bracket style).
        Set<Long> nextRoundTeams = new HashSet<>();
        for (CompetitionTeamInfo cti : nextRoundEntries) nextRoundTeams.add(cti.getTeamId());
        for (CompetitionTeamInfoMatch m : nextRoundMatches) {
            if (m.getTeam1Id() > 0) nextRoundTeams.add(m.getTeam1Id());
            if (m.getTeam2Id() > 0) nextRoundTeams.add(m.getTeam2Id());
        }

        assertFalse(nextRoundTeams.isEmpty(),
                "cup round-" + nextRound + " should have at least one resolved participant after simulating round-" + startRound);

        // Verify: for each match's pair, at least one of the two teams is in the next round.
        int propagated = 0;
        for (Map.Entry<Integer, long[]> e : matchTeams.entrySet()) {
            long t1 = e.getValue()[0], t2 = e.getValue()[1];
            if (t1 <= 0 || t2 <= 0) continue; // placeholder pre-resolved — skip
            boolean t1Through = nextRoundTeams.contains(t1);
            boolean t2Through = nextRoundTeams.contains(t2);
            assertTrue(t1Through || t2Through,
                    "match idx=" + e.getKey() + " of cup " + cup.getName()
                            + " (round " + startRound + "): one of {" + t1 + ", " + t2 + "} must appear in round " + nextRound
                            + ". Got round-" + nextRound + " teams=" + nextRoundTeams);
            assertFalse(t1Through && t2Through,
                    "match idx=" + e.getKey() + ": only one of the two teams can have advanced, not both");
            propagated++;
        }
        assertTrue(propagated > 0, "test should have verified at least one match's propagation");
    }

    // ============================================================
    //  Order 10 — end-of-season composite test.
    //  Seeds final standings for every league + cup, runs
    //  processEndOfSeason(1), then asserts every invariant.
    // ============================================================

    @Test
    @Order(10)
    @DisplayName("End-of-season: promotion/relegation + LoC/SC qualification + cup-pass-through + transfer window")
    void endOfSeason_allInvariantsHold() {
        // -------- SEED: final standings for every league, second league, and cup --------
        // Sorted desc by team reputation, so position 1 = best-reputation team in that competition.
        // This makes the cup "winner" deterministic (team #1 by rep == top of cup TCD).
        List<Competition> leagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1).collect(Collectors.toList());
        List<Competition> secondLeagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 3).collect(Collectors.toList());
        List<Competition> cups = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 2).collect(Collectors.toList());
        for (Competition c : leagues) seedFinalStandings(c.getId());
        for (Competition c : secondLeagues) seedFinalStandings(c.getId());
        for (Competition c : cups) seedFinalStandings(c.getId());

        // Capture pre-state for diff assertions
        long currentSeason = roundRepository.findById(1L).map(Round::getSeason).orElseThrow();
        long nextSeason = currentSeason + 1;
        Map<Long, Set<Long>> teamsByLeagueThisSeason = teamsByCompetitionThisSeason(currentSeason);

        // -------- ACT --------
        seasonTransitionService.processEndOfSeason((int) currentSeason);

        // ============ Invariant 1: TRANSFER WINDOW OPENS ============
        assertTrue(competitionController.isTransferWindowOpen(),
                "processEndOfSeason should open the transfer window for human transfers");

        // ============ Invariant 2: PROMOTION/RELEGATION (Kess pair: comp 3 ↔ comp 5) ============
        // The hardcoded logic in processEndOfSeason: bottom-of-comp-3 (positions 11+)
        // get next-season comp 5; top-of-comp-5 (positions 1-2) get next-season comp 3.
        Optional<Competition> comp3 = competitionRepository.findById(3L);
        Optional<Competition> comp5 = competitionRepository.findById(5L);
        assertTrue(comp3.isPresent() && comp5.isPresent(),
                "Kess league pair (3,5) must exist for the relegation/promotion assertion");

        List<Long> comp3FinalOrder = finalStandingsOrder(3L); // descending by points (best first)
        List<Long> comp5FinalOrder = finalStandingsOrder(5L);

        Set<Long> comp3NextSeason = teamIdsInCompetition(3L, nextSeason);
        Set<Long> comp5NextSeason = teamIdsInCompetition(5L, nextSeason);

        // Relegated: positions 11+ of comp 3 should appear in comp 5 next season
        if (comp3FinalOrder.size() >= 11) {
            for (int i = 10; i < comp3FinalOrder.size(); i++) { // index 10 = position 11
                long teamId = comp3FinalOrder.get(i);
                assertTrue(comp5NextSeason.contains(teamId),
                        "relegated team (position " + (i + 1) + " of comp 3) should be in comp 5 next season; teamId=" + teamId);
                assertFalse(comp3NextSeason.contains(teamId),
                        "relegated team should NOT also be in comp 3 next season; teamId=" + teamId);
            }
        }

        // Promoted: positions 1-2 of comp 5 should appear in comp 3 next season
        if (comp5FinalOrder.size() >= 2) {
            for (int i = 0; i < 2; i++) {
                long teamId = comp5FinalOrder.get(i);
                assertTrue(comp3NextSeason.contains(teamId),
                        "promoted team (position " + (i + 1) + " of comp 5) should be in comp 3 next season; teamId=" + teamId);
                assertFalse(comp5NextSeason.contains(teamId),
                        "promoted team should NOT also be in comp 5 next season; teamId=" + teamId);
            }
        }

        // Every team that was in a league this season is in SOME league next season
        // (either same league, promoted, or relegated — never lost).
        Set<Long> allLeagueTeamsThisSeason = new HashSet<>();
        for (Competition c : leagues) allLeagueTeamsThisSeason.addAll(teamsByLeagueThisSeason.getOrDefault(c.getId(), Set.of()));
        for (Competition c : secondLeagues) allLeagueTeamsThisSeason.addAll(teamsByLeagueThisSeason.getOrDefault(c.getId(), Set.of()));

        Set<Long> allLeagueTeamsNextSeason = new HashSet<>();
        for (Competition c : leagues) allLeagueTeamsNextSeason.addAll(teamIdsInCompetition(c.getId(), nextSeason));
        for (Competition c : secondLeagues) allLeagueTeamsNextSeason.addAll(teamIdsInCompetition(c.getId(), nextSeason));

        for (Long teamId : allLeagueTeamsThisSeason) {
            assertTrue(allLeagueTeamsNextSeason.contains(teamId),
                    "team " + teamId + " was in a league this season but has no league assignment next season");
        }

        // ============ Invariant 3: LoC QUALIFICATION (per coefficient rank) ============
        // Sort leagues by coefficient (same logic the service uses) so we can map league → rank.
        Long locId = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 4).map(Competition::getId).findFirst().orElseThrow();
        Long scId = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 5).map(Competition::getId).findFirst().orElseThrow();

        List<CompetitionTeamInfo> locNextSeasonEntries = ctiRepository.findAllByCompetitionIdAndSeasonNumber(locId, nextSeason);
        assertFalse(locNextSeasonEntries.isEmpty(),
                "LoC must have qualifiers for next season after processEndOfSeason");

        // Each LoC entry's round must be in the configured preliminary/group-entry range.
        int groupStartRound = competitionFormatConfig.get(4).europeanPlan().groupStartRound();
        for (CompetitionTeamInfo cti : locNextSeasonEntries) {
            assertTrue(cti.getRound() >= 0 && cti.getRound() <= groupStartRound,
                    "LoC qualifier round must be before or at the group stage (got "
                            + cti.getRound() + " for team " + cti.getTeamId() + ")");
        }

        // LoC uses the configured tiered entry: direct group entrants, qualifying-round-2
        // entrants, then qualifying-round-1 entrants. The qualification policy and the
        // format plan must stay in sync.
        int expectedTotal = europeanQualificationPolicy.totalEntrants();
        assertEquals(expectedTotal, competitionFormatConfig.get(4).europeanPlan().totalTeams(),
                "LoC format and qualification policy must describe the same entrant total");
        assertEquals(expectedTotal, locNextSeasonEntries.size(),
                "LoC should have exactly " + expectedTotal + " entrants (configurable totalTeams)");
        long atRound0 = locNextSeasonEntries.stream().filter(c -> c.getRound() == 0).count();
        long atRound1 = locNextSeasonEntries.stream().filter(c -> c.getRound() == 1).count();
        long atGroupStage = locNextSeasonEntries.stream().filter(c -> c.getRound() == groupStartRound).count();
        assertEquals(europeanQualificationPolicy.preliminaryEntrants(), atRound0,
                "LoC qualifying-round-1 entry count must match the policy");
        assertEquals(europeanQualificationPolicy.qualifyingEntrants(), atRound1,
                "LoC qualifying-round-2 entry count must match the policy");
        assertEquals(europeanQualificationPolicy.directEntrants(), atGroupStage,
                "LoC direct group-stage entry count must match the policy");

        // No duplicates: a team shouldn't be in LoC twice
        Set<Long> locTeams = new HashSet<>();
        for (CompetitionTeamInfo cti : locNextSeasonEntries) {
            assertTrue(locTeams.add(cti.getTeamId()),
                    "team " + cti.getTeamId() + " is registered for LoC twice");
        }

        // ============ Invariant 4: STARS CUP — CUP WINNERS + PASS-THROUGH ============
        List<CompetitionTeamInfo> scNextSeasonEntries = ctiRepository.findAllByCompetitionIdAndSeasonNumber(scId, nextSeason);
        assertFalse(scNextSeasonEntries.isEmpty(), "Stars Cup must have qualifiers for next season");
        Set<Long> scTeams = scNextSeasonEntries.stream().map(CompetitionTeamInfo::getTeamId).collect(Collectors.toSet());

        // No team should be in both LoC and SC (mutually exclusive European entries)
        for (Long teamId : scTeams) {
            assertFalse(locTeams.contains(teamId),
                    "team " + teamId + " is in BOTH LoC and Stars Cup for next season — must be mutually exclusive");
        }

        // For each nation: the cup winner is the top team of its cup's TCD.
        // Either that cup winner is in SC, OR (if already in LoC) the next non-qualified
        // league team must be in SC. Verify the "reserved cup spot" logic.
        for (Competition cup : cups) {
            long nationId = cup.getNationId();
            Long leagueOfNationId = leagues.stream()
                    .filter(l -> l.getNationId() == nationId)
                    .map(Competition::getId).findFirst().orElse(null);
            if (leagueOfNationId == null) continue; // cup without a matching first league — skip

            Long cupWinnerId = topOfFinalStandings(cup.getId());
            if (cupWinnerId == null) continue;

            boolean cupWinnerInLoc = locTeams.contains(cupWinnerId);
            boolean cupWinnerInSc = scTeams.contains(cupWinnerId);

            if (cupWinnerInLoc) {
                // PASS-THROUGH: cup winner already in LoC → reserved SC spot goes to next non-qualified league team.
                assertFalse(cupWinnerInSc,
                        "cup winner " + cupWinnerId + " (nation " + nationId + ") is in LoC AND SC — pass-through should have removed them from SC");

                // The next non-qualified team from this nation's league must be in SC.
                List<Long> leagueStandings = finalStandingsOrder(leagueOfNationId);
                Long firstNonQualified = null;
                for (Long teamId : leagueStandings) {
                    if (!locTeams.contains(teamId) && !scTeams.contains(teamId)) continue;
                    if (locTeams.contains(teamId)) continue;
                    if (scTeams.contains(teamId)) {
                        firstNonQualified = teamId;
                        break;
                    }
                }
                // Re-scan more carefully: first league team that is NOT in LoC and IS in SC,
                // implying that's the one the pass-through promoted.
                Long passThroughCandidate = null;
                for (Long teamId : leagueStandings) {
                    if (locTeams.contains(teamId)) continue;
                    if (scTeams.contains(teamId)) {
                        passThroughCandidate = teamId;
                        break;
                    }
                }
                assertNotNull(passThroughCandidate,
                        "cup winner " + cupWinnerId + " (nation " + nationId + ") was already in LoC, "
                                + "but no league team from nation " + nationId + " got the pass-through SC spot");
            } else {
                // Cup winner not in LoC → must be in SC (reserved cup spot goes directly to them).
                assertTrue(cupWinnerInSc,
                        "cup winner " + cupWinnerId + " (nation " + nationId + ") is NOT in LoC, so should be in SC. "
                                + "SC teams: " + scTeams);
            }
        }

        // ============ Invariant 5: TeamCompetitionDetail rows exist for every team in every league ============
        // processEndOfSeason ensures TCD entries for teams that played zero matches; verify that.
        for (Competition c : leagues) {
            Set<Long> teamsInLeague = teamsByLeagueThisSeason.getOrDefault(c.getId(), Set.of());
            for (Long teamId : teamsInLeague) {
                TeamCompetitionDetail tcd = tcdRepository.findFirstByTeamIdAndCompetitionId(teamId, c.getId());
                assertNotNull(tcd,
                        "team " + teamId + " in league " + c.getId() + " should have a TeamCompetitionDetail after processEndOfSeason");
            }
        }

        // ============ Invariant 6: IDEMPOTENCE ============
        // Calling processEndOfSeason(1) again must be a no-op (guard flag).
        int locEntriesBeforeSecondCall = ctiRepository.findAllByCompetitionIdAndSeasonNumber(locId, nextSeason).size();
        seasonTransitionService.processEndOfSeason((int) currentSeason);
        int locEntriesAfterSecondCall = ctiRepository.findAllByCompetitionIdAndSeasonNumber(locId, nextSeason).size();
        assertEquals(locEntriesBeforeSecondCall, locEntriesAfterSecondCall,
                "second processEndOfSeason call must be idempotent (guard flag should skip the re-run)");
    }

    // ============================================================
    //  Order 15 — SeasonObjectiveService lifecycle.
    //  Runs after end-of-season (@Order(10)) so evaluate has fired.
    //  Verifies the contract: bootstrap seeded season-1 objectives,
    //  evaluate flipped all of them out of "active".
    // ============================================================

    @Test
    @Order(15)
    @DisplayName("SeasonObjectiveService: bootstrap-generated season 1 objectives, all evaluated after end-of-season")
    void seasonObjectives_lifecycleAcrossEndOfSeason() {
        List<SeasonObjective> season1 = seasonObjectiveRepository.findAll().stream()
                .filter(o -> o.getSeasonNumber() == 1)
                .toList();

        // Bootstrap (initializeRound → generateSeasonObjectives(1)) should have seeded
        // a meaningful number — every team in every league/cup/european comp gets one.
        assertTrue(season1.size() >= 50,
                "bootstrap should have generated >= 50 season-1 objectives, got " + season1.size());

        // Every objective should have non-blank metadata.
        for (SeasonObjective o : season1) {
            assertNotNull(o.getObjectiveType(), "objective " + o.getId() + " missing objectiveType");
            assertNotNull(o.getImportance(),    "objective " + o.getId() + " missing importance");
            assertNotNull(o.getDescription(),   "objective " + o.getId() + " missing description");
        }

        // After processEndOfSeason (@Order(10)) ran evaluateSeasonObjectives(1),
        // no season-1 objective should still be "active" — they're either achieved or failed.
        long stillActive = season1.stream().filter(o -> "active".equals(o.getStatus())).count();
        assertEquals(0, stillActive,
                "evaluateSeasonObjectives should have flipped every season-1 objective out of 'active'; " + stillActive + " still active");

        // And every status must be one of the recognized terminal values.
        Set<String> statuses = season1.stream().map(SeasonObjective::getStatus).collect(Collectors.toSet());
        assertTrue(Set.of("achieved", "failed").containsAll(statuses),
                "all evaluated statuses must be 'achieved' or 'failed', got " + statuses);
    }

    // ============================================================
    //  Order 20 — new-season setup composite test.
    //  Runs after end-of-season (@Order(10)) so the round.season=1
    //  state is in place. Verifies aging, regens, fixtures, etc.
    // ============================================================

    @Test
    @Order(20)
    @DisplayName("New-season setup: round advances, ages bump, regens appear, fixtures generated")
    void newSeasonSetup_allInvariantsHold() {
        long seasonBefore = roundRepository.findById(1L).map(Round::getSeason).orElseThrow();

        // Snapshot: ages of all non-retired players, count of season-2 regens (should start at 0)
        List<Human> playersBefore = humanRepository.findAllByTypeId(1L).stream()
                .filter(h -> !h.isRetired()).collect(Collectors.toList());
        Map<Long, Integer> ageBefore = playersBefore.stream()
                .collect(Collectors.toMap(Human::getId, Human::getAge));
        long regensBeforeSetup = playersBefore.stream()
                .filter(h -> h.getSeasonCreated() == seasonBefore + 1).count();
        assertEquals(0, regensBeforeSetup,
                "no player should have seasonCreated == nextSeason before processNewSeasonSetup runs");

        // Snapshot: personalized tactics exist? If they do, they must be wiped after setup.
        long personalizedTacticsBefore = personalizedTacticRepository.count();

        // -------- ACT --------
        seasonTransitionService.processNewSeasonSetup((int) seasonBefore);

        // ============ Invariant 1: ROUND ADVANCES ============
        Round roundAfter = roundRepository.findById(1L).orElseThrow();
        assertEquals(seasonBefore + 1, roundAfter.getSeason(),
                "round.season must be incremented by 1");
        assertEquals(1L, roundAfter.getRound(),
                "round.round must reset to 1 at start of new season");

        // ============ Invariant 2: AGING — every non-retired player from before is now +1 year ============
        // (some may have retired during this call, so we check non-retired survivors only)
        int agedUp = 0, retired = 0, untouched = 0;
        for (Human before : playersBefore) {
            Human after = humanRepository.findById(before.getId()).orElse(null);
            if (after == null) continue;
            if (after.isRetired()) {
                retired++;
                continue;
            }
            if (after.getAge() == before.getAge() + 1) {
                agedUp++;
            } else if (after.getAge() == before.getAge()) {
                untouched++;
            }
        }
        assertTrue(agedUp > 0, "at least some players should have aged up (+1)");
        assertEquals(0, untouched,
                "no player should be untouched — every surviving non-retired player must have aged exactly +1");
        assertTrue(retired >= 0, "retired count should be >= 0");

        // ============ Invariant 3: REGENS — new players with seasonCreated == nextSeason ============
        long newSeasonRegens = humanRepository.findAllByTypeId(1L).stream()
                .filter(h -> h.getSeasonCreated() == seasonBefore + 1).count();
        assertTrue(newSeasonRegens > 0,
                "processNewSeasonSetup should have generated regens with seasonCreated == nextSeason; got " + newSeasonRegens);

        // ============ Invariant 3b: NEW-SEASON READINESS ==========
        // The reset happens after retirements, expiries and minimum-squad
        // academy promotions, so every active player attached to a club is
        // covered, including newly promoted players.
        List<Human> activeTeamPlayers = humanRepository
                .findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNull(1L);
        assertFalse(activeTeamPlayers.isEmpty(), "new season must contain active club players");
        assertTrue(activeTeamPlayers.stream().allMatch(player -> player.getMorale() == 80.0),
                "every active club player must start the new season with morale 80");
        assertTrue(activeTeamPlayers.stream().allMatch(player -> player.getFitness() == 80.0),
                "every active club player must start the new season with fitness 80");

        // ============ Invariant 4: PERSONALIZED TACTICS CLEARED ============
        // (only if there were any to begin with — bootstrap might not create them)
        if (personalizedTacticsBefore > 0) {
            assertEquals(0, personalizedTacticRepository.count(),
                    "personalized tactics should be wiped at the start of a new season");
        }

        // ============ Invariant 5: NEW SEASON FIXTURES EXIST ============
        // For every first league, round-1 fixtures for the new season must exist.
        List<Competition> leagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1).collect(Collectors.toList());
        for (Competition league : leagues) {
            List<CompetitionTeamInfoMatch> r1NewSeason = ctimRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                    league.getId(), 1L, String.valueOf(seasonBefore + 1));
            assertFalse(r1NewSeason.isEmpty(),
                    "league " + league.getName() + " should have round-1 fixtures for season " + (seasonBefore + 1));
        }

        // ============ Invariant 6: IDEMPOTENCE GUARD ============
        // Calling with the old season number again must skip (round.season already past).
        seasonTransitionService.processNewSeasonSetup((int) seasonBefore);
        Round afterSecondCall = roundRepository.findById(1L).orElseThrow();
        assertEquals(seasonBefore + 1, afterSecondCall.getSeason(),
                "second processNewSeasonSetup(seasonBefore) call must be a no-op (round.season already > season)");
    }

    // ============================================================
    //  Order 25 — SeasonObjectiveService regenerates for the new season.
    //  Runs after new-season setup (@Order(20)) to verify generate fired
    //  for season N+1 as part of the transition.
    // ============================================================

    @Test
    @Order(25)
    @DisplayName("SeasonObjectiveService: new-season setup re-generates objectives for season N+1")
    void seasonObjectives_regeneratedForNewSeason() {
        int newSeason = (int) roundRepository.findById(1L).map(Round::getSeason).orElseThrow().longValue();
        List<SeasonObjective> nextSeasonObjectives = seasonObjectiveRepository.findAll().stream()
                .filter(o -> o.getSeasonNumber() == newSeason)
                .toList();

        // After processNewSeasonSetup → generateSeasonObjectives(newSeason), every team
        // still in a competition should have fresh "active" objectives.
        assertTrue(nextSeasonObjectives.size() >= 50,
                "new-season setup should have generated >= 50 objectives for season " + newSeason
                        + ", got " + nextSeasonObjectives.size());

        // All fresh objectives must start "active".
        long nonActive = nextSeasonObjectives.stream().filter(o -> !"active".equals(o.getStatus())).count();
        assertEquals(0, nonActive,
                "freshly generated objectives must all be 'active', " + nonActive + " were not");
    }

    // ============================================================
    //  Helpers
    // ============================================================

    /** Find the first cup that has fixtures for the given round (season 1). */
    private Competition findFirstCupWithRoundFixtures(long round) {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 2)
                .filter(c -> !ctimRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(c.getId(), round, "1").isEmpty())
                .findFirst().orElse(null);
    }

    /**
     * Seed a TCD row per team in the competition (season 1) with descending points
     * by team reputation, so position 1 = highest-reputation team. This produces
     * deterministic standings without needing to simulate matches.
     */
    private void seedFinalStandings(long competitionId) {
        Set<Long> teamIds = ctiRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, 1L).stream()
                .map(CompetitionTeamInfo::getTeamId).collect(Collectors.toSet());
        if (teamIds.isEmpty()) return;

        List<Team> teams = teamRepository.findAllById(teamIds);
        teams.sort((a, b) -> b.getReputation() - a.getReputation()); // best reputation first

        int n = teams.size();
        for (int rank = 0; rank < n; rank++) {
            Team t = teams.get(rank);
            int points = (n - rank) * 3; // simplest descending stagger; ensures unique sort key
            int wins = points / 3;
            int gf = (n - rank) * 2;
            int ga = rank * 2;

            TeamCompetitionDetail tcd = tcdRepository.findFirstByTeamIdAndCompetitionId(t.getId(), competitionId);
            if (tcd == null) {
                tcd = new TeamCompetitionDetail();
                tcd.setTeamId(t.getId());
                tcd.setCompetitionId(competitionId);
                tcd.setForm("");
            }
            tcd.setPoints(points);
            tcd.setWins(wins);
            tcd.setDraws(0);
            tcd.setLoses(n - 1 - wins);
            tcd.setGoalsFor(gf);
            tcd.setGoalsAgainst(ga);
            tcd.setGoalDifference(gf - ga);
            tcd.setGames(n - 1);
            tcdRepository.save(tcd);
        }
    }

    /** Final standings, sorted descending by points (best team first). */
    private List<Long> finalStandingsOrder(long competitionId) {
        return tcdRepository.findAll().stream()
                .filter(t -> t.getCompetitionId() == competitionId)
                .sorted((a, b) -> {
                    if (a.getPoints() != b.getPoints()) return Integer.compare(b.getPoints(), a.getPoints());
                    if (a.getGoalDifference() != b.getGoalDifference()) return Integer.compare(b.getGoalDifference(), a.getGoalDifference());
                    return Integer.compare(b.getGoalsFor(), a.getGoalsFor());
                })
                .map(TeamCompetitionDetail::getTeamId)
                .collect(Collectors.toList());
    }

    private Long topOfFinalStandings(long competitionId) {
        List<Long> ordered = finalStandingsOrder(competitionId);
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    private Set<Long> teamIdsInCompetition(long competitionId, long seasonNumber) {
        return ctiRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber).stream()
                .map(CompetitionTeamInfo::getTeamId)
                .collect(Collectors.toSet());
    }

    private Map<Long, Set<Long>> teamsByCompetitionThisSeason(long seasonNumber) {
        Map<Long, Set<Long>> out = new LinkedHashMap<>();
        for (CompetitionTeamInfo cti : ctiRepository.findAllBySeasonNumber(seasonNumber)) {
            out.computeIfAbsent(cti.getCompetitionId(), k -> new HashSet<>()).add(cti.getTeamId());
        }
        return out;
    }
}
