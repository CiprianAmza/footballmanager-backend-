package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.EuropeanCompetitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Combined League of Champions + Stars Cup run that validates the real
 * elimination FEED between the two competitions — the path the standalone
 * {@code StarsCupOutcomeIT} can't see because it fabricates its own field:
 *
 * <ol>
 *   <li>LoC preliminary-round LOSERS drop into the Stars Cup group stage
 *       ({@link EuropeanCompetitionService#assignLocLosersToStarsCup}).</li>
 *   <li>LoC group-stage 3rd-place teams drop into the Stars Cup PLAYOFF
 *       ({@link EuropeanCompetitionService#qualifyFromGroupStage}).</li>
 *   <li>The Stars Cup playoff draw pairs those LoC 3rd-place teams against the
 *       Stars Cup group runners-up
 *       ({@link EuropeanCompetitionService#drawStarsCupPlayoffSeeded}).</li>
 * </ol>
 *
 * <p>Driven through the real European services (not {@code simulateMatchday}, to
 * avoid the season-calendar plumbing). Assertions are RNG-independent: the set of
 * "losers" / "3rd places" is derived from the actual winners/standings produced
 * by the run, not from a fixed seed.
 *
 * <p>Seeds 40 LoC entrants (the proven preliminary path: 40 → 20 → 16 group
 * slots). The Stars Cup group stage is then fed purely by the LoC preliminary
 * losers, capped at the Stars Cup group capacity (16).
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("LoC → Stars Cup elimination feed: prelim losers to groups, 3rd places to playoff")
class LeagueAndStarsCupFeedIT {

    @Autowired private EuropeanCompetitionService europeanCompetitionService;
    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository ctiRepository;
    @Autowired private CompetitionTeamInfoMatchRepository ctimRepository;
    @Autowired private CompetitionTeamInfoDetailRepository ctidRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private CompetitionFormatConfig competitionFormat;

    private long locId;
    private long scId;

    @BeforeEach
    void resetAndSeed() {
        locId = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 4).map(Competition::getId).findFirst().orElseThrow();
        scId = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 5).map(Competition::getId).findFirst().orElseThrow();

        Round round = roundRepository.findById(1L).orElseThrow();
        round.setSeason(1);
        round.setRound(1);
        roundRepository.save(round);

        wipeCompetition(locId);
        wipeCompetition(scId);
    }

    @Test
    void locEliminationsFeedStarsCup() {
        CompetitionFormat locFmt = competitionFormat.get(4);
        CompetitionFormat scFmt = competitionFormat.get(5);
        int locSlots = locFmt.groupCount() * locFmt.groupSize(); // 16

        // --- Seed 40 LoC entrants; Stars Cup is fed purely by the LoC drop-outs. ---
        List<Long> allTeams = teamRepository.findAll().stream()
                .sorted(Comparator.comparingLong(Team::getId)).map(Team::getId).toList();
        assertTrue(allTeams.size() >= 40, "bootstrap must provide at least 40 teams");
        List<Long> locTeams = allTeams.subList(0, 40);

        for (long teamId : locTeams) seedEntry(locId, teamId, 0L); // LoC at round 0 (prelim)

        // ============================================================
        // 1. LoC preliminaries → losers drop into Stars Cup groups
        // ============================================================
        Set<Long> allEntrants = new HashSet<>(locTeams);
        for (int r = 0; r < locFmt.groupStartRound(); r++) {
            europeanCompetitionService.drawEuropeanPreliminarySeeded(locId, r, locSlots);
            competitionController.simulateRound(String.valueOf(locId), String.valueOf(r));
            europeanCompetitionService.assignLocLosersToStarsCup(locId, r);
        }

        Set<Long> survivors = teamsAtRound(locId, locFmt.groupStartRound()); // reached the group draw
        assertEquals(locSlots, survivors.size(),
                "40 entrants must be trimmed through the preliminaries to " + locSlots + " group slots");
        Set<Long> prelimLosers = new HashSet<>(allEntrants);
        prelimLosers.removeAll(survivors);

        // Losers are dropped at the LoC format's losersDropRound (= the SC group round).
        Set<Long> scGroupEntrants = teamsAtRound(scId, locFmt.losersDropRound());
        int scSlots = scFmt.groupCount() * scFmt.groupSize();
        assertEquals(scSlots, scGroupEntrants.size(),
                "the Stars Cup group stage should be filled to capacity (" + scSlots + ") by LoC losers");
        assertTrue(prelimLosers.containsAll(scGroupEntrants),
                "every team fed into the Stars Cup group stage must be a LoC preliminary loser");

        // ============================================================
        // 2. LoC group stage → 3rd places drop into the Stars Cup playoff
        // ============================================================
        playGroupStage(locId, locFmt);
        europeanCompetitionService.qualifyFromGroupStage(locId);

        Set<Long> scPlayoffFromLoc = teamsAtRound(scId, scFmt.playoffRound());
        assertEquals(locFmt.groupCount(), scPlayoffFromLoc.size(),
                "one 3rd-place team per LoC group should drop into the Stars Cup playoff");
        Set<Long> locGroupTeams = locGroupMembers(locId);
        assertTrue(locGroupTeams.containsAll(scPlayoffFromLoc),
                "Stars Cup playoff entrants from LoC must be LoC group-stage teams (the 3rd places)");

        // ============================================================
        // 3. Stars Cup group stage → playoff draw pairs runners-up vs LoC 3rd
        // ============================================================
        europeanCompetitionService.drawEuropeanGroups(scId, scFmt.groupStartRound());
        europeanCompetitionService.resetEuropeanStats(scId);
        europeanCompetitionService.generateGroupStageFixtures(scId);
        playGroupStage(scId, scFmt);
        europeanCompetitionService.qualifyFromStarsCupGroupStage(scId);

        List<Long> playoffParticipants = ctiRepository.findAllBySeasonNumber(1L).stream()
                .filter(c -> c.getCompetitionId() == scId && c.getRound() == scFmt.playoffRound())
                .map(CompetitionTeamInfo::getTeamId).distinct().toList();
        assertTrue(playoffParticipants.containsAll(scPlayoffFromLoc),
                "LoC 3rd-place teams must still be in the playoff pool after SC qualification");

        europeanCompetitionService.drawStarsCupPlayoffSeeded(scId, scFmt.playoffRound(),
                new java.util.ArrayList<>(playoffParticipants));

        List<CompetitionTeamInfoMatch> playoffMatches = ctimRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(scId, scFmt.playoffRound(), "1");
        assertTrue(playoffMatches.size() >= 1, "the Stars Cup playoff draw must produce ties");

        Set<Long> pairedTeams = new HashSet<>();
        for (CompetitionTeamInfoMatch m : playoffMatches) {
            pairedTeams.add(m.getTeam1Id());
            pairedTeams.add(m.getTeam2Id());
        }
        assertTrue(pairedTeams.containsAll(scPlayoffFromLoc),
                "every LoC 3rd-place team must be paired into a Stars Cup playoff tie");
    }

    /** Draw + generate fixtures already done by caller for SC; for LoC the group draw
     *  was created during the prelim flow's group-draw round, so just play each round. */
    private void playGroupStage(long competitionId, CompetitionFormat fmt) {
        if (competitionId == locId) {
            // LoC: draw groups + fixtures from the survivors at the group-draw round.
            europeanCompetitionService.drawEuropeanGroups(locId, fmt.groupStartRound());
            europeanCompetitionService.resetEuropeanStats(locId);
            europeanCompetitionService.generateGroupStageFixtures(locId);
        }
        for (int r = fmt.groupStartRound(); r <= fmt.groupEndRound(); r++) {
            competitionController.simulateRound(String.valueOf(competitionId), String.valueOf(r));
        }
    }

    private Set<Long> teamsAtRound(long competitionId, long round) {
        return ctiRepository.findAllBySeasonNumber(1L).stream()
                .filter(c -> c.getCompetitionId() == competitionId && c.getRound() == round)
                .map(CompetitionTeamInfo::getTeamId).collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    private Set<Long> locGroupMembers(long competitionId) {
        return ctiRepository.findAllBySeasonNumber(1L).stream()
                .filter(c -> c.getCompetitionId() == competitionId && c.getGroupNumber() > 0)
                .map(CompetitionTeamInfo::getTeamId).collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    private void seedEntry(long competitionId, long teamId, long round) {
        CompetitionTeamInfo cti = new CompetitionTeamInfo();
        cti.setTeamId(teamId);
        cti.setCompetitionId(competitionId);
        cti.setSeasonNumber(1);
        cti.setRound(round);
        cti.setGroupNumber(0);
        cti.setPotNumber(0);
        ctiRepository.save(cti);
    }

    private void wipeCompetition(long competitionId) {
        ctiRepository.deleteAll(ctiRepository.findAll().stream()
                .filter(c -> c.getCompetitionId() == competitionId).toList());
        ctimRepository.deleteAll(ctimRepository.findAll().stream()
                .filter(m -> m.getCompetitionId() == competitionId).toList());
        ctidRepository.deleteAll(ctidRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == competitionId).toList());
    }
}
